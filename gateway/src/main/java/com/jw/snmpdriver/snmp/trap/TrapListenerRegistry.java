package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Gateway-wide Trap Listeners keyed by port; the first acquirer's bindAddress defines the listener. */
public final class TrapListenerRegistry {
    static final long RETRY_SECONDS = 30;
    static final long SOURCE_REFRESH_MINUTES = 5;

    private final ListenerBinder binder;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, Listener> listeners = new HashMap<>();

    public TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler) {
        this.binder = Objects.requireNonNull(binder);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /** Startup guard: a failing factory yields null instead of failing the caller. */
    public static TrapListenerRegistry tryCreate(
            Supplier<TrapListenerRegistry> factory, Consumer<RuntimeException> onFailure) {
        try {
            return factory.get();
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return null;
        }
    }

    public TrapListenerHandle acquire(String deviceName, String bindAddress, int port) {
        return acquire(deviceName, bindAddress, port, null);
    }

    /** {@code onChange} runs after a background bind retry settles, until the handle is released. */
    public TrapListenerHandle acquire(String deviceName, String bindAddress, int port, Runnable onChange) {
        return acquire(deviceName, bindAddress, port, onChange, null);
    }

    /** A non-null {@code recipient} also receives the Notifications whose source belongs to it. */
    public synchronized TrapListenerHandle acquire(
            String deviceName, String bindAddress, int port, Runnable onChange, TrapRecipient recipient) {
        InetAddress address = parseIpLiteral(bindAddress);
        if (address == null) {
            return rejected("设备 " + deviceName + " 的 Trap bindAddress \"" + bindAddress + "\" 不是 IP 字面量");
        }
        Listener listener = listeners.get(port);
        if (listener == null) {
            listener = new Listener(port, address);
            listeners.put(port, listener);
            tryBind(listener);
        } else if (!listener.address.equals(address)) {
            return rejected("端口 " + port + " 已由设备 " + listener.holders.get(0) + " 以 "
                    + listener.address.getHostAddress() + " 确定为 Trap Listener，设备 " + deviceName
                    + " 请求的 bindAddress " + address.getHostAddress() + " 不一致");
        }
        listener.refCount++;
        listener.holders.add(deviceName);
        if (onChange != null) {
            listener.watchers.add(onChange);
        }
        ScheduledFuture<?> refresh = null;
        if (recipient != null) {
            listener.router.add(recipient);
            refresh = scheduleSourceRefresh(recipient);
        }
        return new TrapListenerHandle(this, listener, null, deviceName, onChange, recipient, refresh);
    }

    public synchronized void closeAll() {
        for (Listener listener : new ArrayList<>(listeners.values())) {
            close(listener);
        }
        listeners.clear();
    }

    synchronized void release(Listener listener, String deviceName, Runnable onChange,
                              TrapRecipient recipient, ScheduledFuture<?> refresh) {
        if (refresh != null) {
            refresh.cancel(false);
        }
        if (recipient != null) {
            listener.router.remove(recipient);
        }
        listener.holders.remove(deviceName);
        if (onChange != null) {
            listener.watchers.remove(onChange);
        }
        if (listeners.get(listener.port) != listener || --listener.refCount > 0) {
            return;
        }
        listeners.remove(listener.port);
        close(listener);
    }

    synchronized TrapListenerState stateOf(Listener listener) {
        return listener.state;
    }

    synchronized String lastErrorOf(Listener listener) {
        return listener.lastError;
    }

    private TrapListenerHandle rejected(String reason) {
        return new TrapListenerHandle(this, null, reason, null, null, null, null);
    }

    /** Host DNS is re-resolved off the transport thread; the first run happens immediately. */
    private ScheduledFuture<?> scheduleSourceRefresh(TrapRecipient recipient) {
        Runnable refresh = () -> {
            TrapFilter filter = recipient.filter();
            filter.refreshSources();
            if (filter.resolveError() != null) {
                recipient.notice().accept(filter.resolveError());
            }
        };
        try {
            return scheduler.scheduleWithFixedDelay(refresh, 0, SOURCE_REFRESH_MINUTES, TimeUnit.MINUTES);
        } catch (RejectedExecutionException error) {
            return null;
        }
    }

    synchronized long unmatchedCountOf(Listener listener) {
        return listener.router.unmatchedCount();
    }

    synchronized long multiMatchCountOf(Listener listener) {
        return listener.router.multiMatchCount();
    }

    private void tryBind(Listener listener) {
        Closeable bound = null;
        Exception failure = null;
        try {
            bound = binder.bind(listener.address, listener.port, listener.router);
        } catch (IOException | RuntimeException error) {
            failure = error;
        }
        settle(listener, bound, failure);
    }

    private void settle(Listener listener, Closeable bound, Exception failure) {
        if (failure == null) {
            listener.bound = bound;
            listener.state = TrapListenerState.BOUND;
            listener.lastError = null;
            return;
        }
        listener.state = TrapListenerState.FAILED;
        listener.lastError = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        scheduleRetry(listener);
    }

    private void scheduleRetry(Listener listener) {
        try {
            listener.retry = scheduler.schedule(() -> retry(listener), RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException error) {
            listener.retry = null;
        }
    }

    private boolean retryable(Listener listener) {
        return listener.state == TrapListenerState.FAILED && listener.refCount > 0
                && listeners.get(listener.port) == listener;
    }

    private void retry(Listener listener) {
        synchronized (this) {
            if (!retryable(listener)) {
                return;
            }
        }
        // bind may block; keep it outside the registry lock
        Closeable bound = null;
        Exception failure = null;
        try {
            bound = binder.bind(listener.address, listener.port, listener.router);
        } catch (IOException | RuntimeException error) {
            failure = error;
        }
        List<Runnable> watchers;
        synchronized (this) {
            if (!retryable(listener)) {
                closeQuietly(bound); // released or replaced while binding
                return;
            }
            settle(listener, bound, failure);
            watchers = new ArrayList<>(listener.watchers);
        }
        // outside the registry lock so watchers may read handle state freely
        for (Runnable watcher : watchers) {
            try {
                watcher.run();
            } catch (RuntimeException ignored) {
                // a failing watcher must not stop the others
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // port release is best effort; the listener is dropped either way
        }
    }

    private void close(Listener listener) {
        if (listener.retry != null) {
            listener.retry.cancel(false);
            listener.retry = null;
        }
        closeQuietly(listener.bound);
        listener.bound = null;
        listener.state = TrapListenerState.CLOSED;
    }

    /** Strict IP literal (dotted-quad IPv4 or colon IPv6); null otherwise, never triggers DNS. */
    public static InetAddress parseIpLiteral(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        boolean ipv6 = trimmed.indexOf(':') >= 0;
        if (ipv6 ? !trimmed.matches("[0-9a-fA-F:.]+") : !trimmed.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            return null;
        }
        try {
            return InetAddress.getByName(trimmed);
        } catch (UnknownHostException error) {
            return null;
        }
    }

    static final class Listener {
        final int port;
        final InetAddress address;
        final List<String> holders = new ArrayList<>();
        int refCount;
        TrapListenerState state = TrapListenerState.FAILED;
        String lastError;
        Closeable bound;
        ScheduledFuture<?> retry;
        final List<Runnable> watchers = new ArrayList<>();
        final NotificationRouter router;

        Listener(int port, InetAddress address) {
            this.port = port;
            this.address = address;
            this.router = new NotificationRouter(port);
        }

        String endpoint() {
            return address.getHostAddress() + ":" + port;
        }
    }
}
