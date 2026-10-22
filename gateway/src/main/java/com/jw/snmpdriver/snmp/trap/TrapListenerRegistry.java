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
import java.util.regex.Pattern;

/** Gateway-wide Trap Listeners keyed by port; the first acquirer's bindAddress defines the listener. */
public final class TrapListenerRegistry {
    static final long RETRY_SECONDS = 30;
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

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

    public synchronized TrapListenerHandle acquire(String deviceName, String bindAddress, int port) {
        InetAddress address = parseLiteral(bindAddress);
        if (address == null) {
            return rejected("设备 " + deviceName + " 的 Trap bindAddress \"" + bindAddress + "\" 不是 IP 字面量");
        }
        Listener listener = listeners.get(port);
        if (listener == null) {
            listener = new Listener(port, address, deviceName);
            listeners.put(port, listener);
            tryBind(listener);
        } else if (!listener.address.equals(address)) {
            return rejected("端口 " + port + " 已由设备 " + listener.owner + " 以 "
                    + listener.address.getHostAddress() + " 确定为 Trap Listener，设备 " + deviceName
                    + " 请求的 bindAddress " + address.getHostAddress() + " 不一致");
        }
        listener.refCount++;
        return new TrapListenerHandle(this, listener, null);
    }

    public synchronized void closeAll() {
        for (Listener listener : new ArrayList<>(listeners.values())) {
            close(listener);
        }
        listeners.clear();
    }

    synchronized void release(Listener listener) {
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
        return new TrapListenerHandle(this, null, reason);
    }

    private void tryBind(Listener listener) {
        try {
            listener.bound = binder.bind(listener.address, listener.port);
            listener.state = TrapListenerState.BOUND;
            listener.lastError = null;
        } catch (IOException | RuntimeException error) {
            listener.state = TrapListenerState.FAILED;
            listener.lastError = error.getMessage() == null ? error.toString() : error.getMessage();
            scheduleRetry(listener);
        }
    }

    private void scheduleRetry(Listener listener) {
        try {
            listener.retry = scheduler.schedule(() -> retry(listener), RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException error) {
            listener.retry = null;
        }
    }

    private synchronized void retry(Listener listener) {
        if (listener.state != TrapListenerState.FAILED || listener.refCount == 0
                || listeners.get(listener.port) != listener) {
            return;
        }
        tryBind(listener);
    }

    private void close(Listener listener) {
        if (listener.retry != null) {
            listener.retry.cancel(false);
            listener.retry = null;
        }
        if (listener.bound != null) {
            try {
                listener.bound.close();
            } catch (IOException | RuntimeException ignored) {
                // port release is best effort; the listener is dropped either way
            }
            listener.bound = null;
        }
        listener.state = TrapListenerState.CLOSED;
    }

    private static InetAddress parseLiteral(String text) {
        if (text == null || !IP_LITERAL.matcher(text.trim()).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(text.trim());
        } catch (UnknownHostException error) {
            return null;
        }
    }

    static final class Listener {
        final int port;
        final InetAddress address;
        final String owner;
        int refCount;
        TrapListenerState state = TrapListenerState.FAILED;
        String lastError;
        Closeable bound;
        ScheduledFuture<?> retry;

        Listener(int port, InetAddress address, String owner) {
            this.port = port;
            this.address = address;
            this.owner = owner;
        }

        String endpoint() {
            return address.getHostAddress() + ":" + port;
        }
    }
}
