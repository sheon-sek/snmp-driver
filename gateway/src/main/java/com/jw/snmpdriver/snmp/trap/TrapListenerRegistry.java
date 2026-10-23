package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.jw.snmpdriver.snmp.runtime.LicenseGate;

/** Gateway-wide Trap Listeners keyed by port; the first acquirer's bindAddress defines the listener. */
public final class TrapListenerRegistry {
    static final long RETRY_SECONDS = 30;
    static final long SOURCE_REFRESH_MINUTES = 5;
    static final long LICENSE_WATCH_SECONDS = 10;
    static final int RESOLVE_QUEUE = 64;

    private final ListenerBinder binder;
    private final ScheduledExecutorService scheduler;
    private final Supplier<LicenseGate.Verdict> license;
    private final boolean watchLicense;
    /** Loaded (boots+1, written) before this registry exists, so no listener binds before the identity is saved. */
    private final TrapEngineIdentity engineIdentity;
    /** The local engine clock, started with the identity: every Listener's USM reads engineTime from it. */
    private final LongSupplier nanos;
    private final long engineStartNanos;
    private final Map<Integer, Listener> listeners = new HashMap<>();
    private LicenseGate.Verdict lastVerdict;
    private ScheduledFuture<?> licenseWatch;
    /** Host DNS runs here, so a slow lookup never occupies the shared polling scheduler. */
    private volatile ExecutorService resolver;

    /** Treats every Notification as licensed and never polls the License; for tests and bind-only use. */
    public TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler) {
        this(binder, scheduler, () -> LicenseGate.Verdict.LICENSED, false, null);
    }

    /** {@code license} is polled every {@value #LICENSE_WATCH_SECONDS} s so Traps/Status follows License changes. */
    public TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler,
                                Supplier<LicenseGate.Verdict> license) {
        this(binder, scheduler, license, true, null);
    }

    /** {@code engineIdentity} is the Gateway-wide local engine identity, already persisted by the caller. */
    public TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler,
                                Supplier<LicenseGate.Verdict> license, TrapEngineIdentity engineIdentity) {
        this(binder, scheduler, license, engineIdentity, System::nanoTime);
    }

    TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler,
                         Supplier<LicenseGate.Verdict> license, TrapEngineIdentity engineIdentity, LongSupplier nanos) {
        this(binder, scheduler, license, true, Objects.requireNonNull(engineIdentity), nanos);
    }

    private TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler,
                                 Supplier<LicenseGate.Verdict> license, boolean watchLicense,
                                 TrapEngineIdentity engineIdentity) {
        this(binder, scheduler, license, watchLicense, engineIdentity, System::nanoTime);
    }

    private TrapListenerRegistry(ListenerBinder binder, ScheduledExecutorService scheduler,
                                 Supplier<LicenseGate.Verdict> license, boolean watchLicense,
                                 TrapEngineIdentity engineIdentity, LongSupplier nanos) {
        this.nanos = Objects.requireNonNull(nanos);
        this.engineStartNanos = nanos.getAsLong();
        this.binder = Objects.requireNonNull(binder);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.license = Objects.requireNonNull(license);
        this.watchLicense = watchLicense;
        this.engineIdentity = engineIdentity;
    }

    /** Empty only for registries built without an identity (tests, bind-only use). */
    public Optional<TrapEngineIdentity> engineIdentity() {
        return Optional.ofNullable(engineIdentity);
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
    public TrapListenerHandle acquire(
            String deviceName, String bindAddress, int port, Runnable onChange, TrapRecipient recipient) {
        List<Runnable> changed = new ArrayList<>();
        TrapListenerHandle handle = acquireLocked(deviceName, bindAddress, port, onChange, recipient, changed);
        runAll(changed);
        return handle;
    }

    private synchronized TrapListenerHandle acquireLocked(String deviceName, String bindAddress, int port,
                                                          Runnable onChange, TrapRecipient recipient,
                                                          List<Runnable> changed) {
        InetAddress address = parseIpLiteral(bindAddress);
        if (address == null) {
            return rejected("设备 " + deviceName + " 的 Trap bindAddress \"" + bindAddress + "\" 不是 IP 字面量");
        }
        Listener listener = listeners.get(port);
        if (listener == null) {
            listener = new Listener(port, address, license, newUsmStack());
            if (listener.usm.informEnabled()) {
                listener.router.v3Inform(listener.usm.localEngineId(), v3InformUnavailable());
            }
            listeners.put(port, listener);
            listener.router.start();
            startLicenseWatch();
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
        if (recipient != null) {
            if (recipient.v3User() != null) {
                listener.v3Recipients.put(recipient, onChange);
                reconcileV3(listener, changed);
                changed.remove(onChange); // the acquirer reads the result from its handle
            }
            listener.router.add(recipient);
            startResolver();
            ScheduledFuture<?> refresh = scheduleSourceRefresh(recipient);
            if (refresh != null) {
                listener.refreshes.put(recipient, refresh);
            }
        }
        return new TrapListenerHandle(this, listener, null, deviceName, onChange, recipient);
    }

    public synchronized void closeAll() {
        if (licenseWatch != null) {
            licenseWatch.cancel(false);
            licenseWatch = null;
        }
        if (resolver != null) {
            resolver.shutdownNow();
            resolver = null;
        }
        for (Listener listener : new ArrayList<>(listeners.values())) {
            close(listener);
        }
        listeners.clear();
    }

    private void startLicenseWatch() {
        if (!watchLicense || licenseWatch != null) {
            return;
        }
        lastVerdict = license.get();
        try {
            licenseWatch = scheduler.scheduleWithFixedDelay(
                    this::checkLicense, LICENSE_WATCH_SECONDS, LICENSE_WATCH_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException error) {
            licenseWatch = null;
        }
    }

    /** Tells every Device's diagnostics when the License flips, even if no packet arrives. */
    private void checkLicense() {
        LicenseGate.Verdict verdict = license.get();
        List<Runnable> watchers = new ArrayList<>();
        synchronized (this) {
            if (verdict == lastVerdict) {
                return;
            }
            lastVerdict = verdict;
            for (Listener listener : listeners.values()) {
                watchers.addAll(listener.watchers);
            }
        }
        for (Runnable watcher : watchers) {
            try {
                watcher.run();
            } catch (RuntimeException ignored) {
                // a failing watcher must not stop the others
            }
        }
    }

    void release(Listener listener, String deviceName, Runnable onChange, TrapRecipient recipient) {
        List<Runnable> changed = new ArrayList<>();
        releaseLocked(listener, deviceName, onChange, recipient, changed);
        runAll(changed);
    }

    private synchronized void releaseLocked(Listener listener, String deviceName, Runnable onChange,
                                            TrapRecipient recipient, List<Runnable> changed) {
        if (recipient != null) {
            listener.router.remove(recipient);
            if (listener.v3Recipients.remove(recipient) != null || recipient.v3User() != null) {
                listener.conflicts.remove(recipient);
                reconcileV3(listener, changed);
            }
            ScheduledFuture<?> refresh = listener.refreshes.remove(recipient);
            if (refresh != null) {
                refresh.cancel(false);
            }
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

    /**
     * Same-named USM users on one Listener authenticate before routing, so they must share SecretConfig
     * references, protocols and plaintext (#37, ADR 0007). Every Device of a mismatched name is Degraded and
     * left out of USM, so the outcome never depends on start order; the rest are registered. Watchers of
     * Devices whose conflict changed are collected into {@code changed}.
     */
    private void reconcileV3(Listener listener, List<Runnable> changed) {
        Map<String, List<TrapRecipient>> byName = new LinkedHashMap<>();
        for (TrapRecipient recipient : listener.v3Recipients.keySet()) {
            byName.computeIfAbsent(recipient.v3User().securityName(), name -> new ArrayList<>()).add(recipient);
        }
        Set<TrapRecipient> wanted = new HashSet<>();
        for (Map.Entry<String, List<TrapRecipient>> group : byName.entrySet()) {
            List<TrapRecipient> members = group.getValue();
            TrapV3User first = members.get(0).v3User();
            boolean mismatch = members.stream().anyMatch(member -> !member.v3User().sameCredentials(first));
            for (TrapRecipient member : members) {
                String conflict = mismatch ? conflictText(listener, group.getKey(), member, members) : null;
                String previous = conflict == null
                        ? listener.conflicts.remove(member) : listener.conflicts.put(member, conflict);
                if (!Objects.equals(previous, conflict)) {
                    Runnable watcher = listener.v3Recipients.get(member);
                    if (watcher != null) {
                        changed.add(watcher);
                    }
                }
                if (!mismatch) {
                    wanted.add(member);
                }
            }
        }
        for (TrapRecipient registered : new ArrayList<>(listener.v3Registered)) {
            if (!wanted.contains(registered)) {
                listener.v3Registered.remove(registered);
                listener.usm.unregister(registered.v3User());
            }
        }
        for (TrapRecipient recipient : wanted) {
            if (listener.v3Registered.contains(recipient)) {
                continue;
            }
            // the user is localized now, never on the fly for whatever engineID a packet claims (ADR 0003)
            try {
                listener.usm.register(recipient.v3User());
                listener.v3Registered.add(recipient);
            } catch (IllegalArgumentException error) {
                // validate bounds engineID length; anything else must not leave the listener half-acquired
                recipient.notice().accept("无法注册 v3 Trap 用户：" + error.getMessage());
            }
        }
    }

    private static String conflictText(Listener listener, String securityName, TrapRecipient self,
                                       List<TrapRecipient> members) {
        Set<String> others = new TreeSet<>();
        for (TrapRecipient member : members) {
            if (member != self) {
                others.add(member.deviceName());
            }
        }
        return "Trap Listener " + listener.endpoint() + " 上 securityName \"" + securityName + "\" 与设备 "
                + String.join("、", others) + " 的 SecretConfig 引用、协议或口令不一致，这些设备均不接收 v3 "
                + "Trap/Inform。请统一同名用户的配置。";
    }

    /** Null when the recipient has no securityName conflict on its Listener. */
    String conflictOf(Listener listener, TrapRecipient recipient) {
        return recipient == null ? null : listener.conflicts.get(recipient);
    }

    private static void runAll(List<Runnable> watchers) {
        for (Runnable watcher : watchers) {
            try {
                watcher.run();
            } catch (RuntimeException ignored) {
                // a failing watcher must not stop the others
            }
        }
    }

    // Lock-free on purpose: the transport thread reads these while close() holds the registry lock
    // and waits for that same thread to finish.
    TrapListenerState stateOf(Listener listener) {
        return listener.state;
    }

    String lastErrorOf(Listener listener) {
        return listener.lastError;
    }

    private TrapListenerHandle rejected(String reason) {
        return new TrapListenerHandle(this, null, reason, null, null, null);
    }

    private void startResolver() {
        if (resolver != null) {
            return;
        }
        // one daemon thread and a bounded queue; refreshes beyond it are dropped until the next period
        resolver = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(RESOLVE_QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "snmp-trap-dns");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
    }

    /** The scheduler only triggers; host DNS is re-resolved on the resolver. The first run happens immediately. */
    private ScheduledFuture<?> scheduleSourceRefresh(TrapRecipient recipient) {
        Runnable refresh = () -> {
            TrapFilter filter = recipient.filter();
            filter.refreshSources();
            if (filter.resolveError() != null) {
                recipient.notice().accept(filter.resolveError());
            }
        };
        Runnable trigger = () -> {
            ExecutorService current = resolver;
            if (current == null) {
                return;
            }
            try {
                current.execute(refresh);
            } catch (RejectedExecutionException ignored) {
                // registry closed meanwhile
            }
        };
        try {
            return scheduler.scheduleWithFixedDelay(trigger, 0, SOURCE_REFRESH_MINUTES, TimeUnit.MINUTES);
        } catch (RejectedExecutionException error) {
            return null;
        }
    }

    /**
     * One private stack per Listener, on the Gateway engine identity: v3 Informs authenticate under it. Users stay
     * registered while the identity cannot back an Inform, so the router can say why it is not ACKed.
     */
    private TrapUsmStack newUsmStack() {
        if (engineIdentity == null) {
            return new TrapUsmStack(org.snmp4j.mp.MPv3.createLocalEngineID(), 1);
        }
        return new TrapUsmStack(engineIdentity.engineId(), engineIdentity.engineBoots(), localEngineTime(), true);
    }

    /** Seconds since the identity's boots began; never goes back for a rebuilt Listener or another port. */
    int localEngineTime() {
        long seconds = (nanos.getAsLong() - engineStartNanos) / 1_000_000_000L;
        return (int) Math.min(Math.max(seconds, 0), Integer.MAX_VALUE);
    }

    /** Traps/LastError for a v3 Inform the identity cannot back (ADR 0009), or null when it can. */
    private String v3InformUnavailable() {
        if (engineIdentity.informAvailable()) {
            return null;
        }
        String notice = engineIdentity.notice();
        return notice != null ? notice : "Trap 本地引擎身份不可用，v3 Inform 已停用，不 ACK。";
    }

    synchronized TrapUsmStack usmStackOf(int port) {
        Listener listener = listeners.get(port);
        return listener == null ? null : listener.usm;
    }

    // Lock-free too: router counters are atomics, and TrapDiagnostics reads them under its own lock.
    long unmatchedCountOf(Listener listener) {
        return listener.router.unmatchedCount();
    }

    long multiMatchCountOf(Listener listener) {
        return listener.router.multiMatchCount();
    }

    private void tryBind(Listener listener) {
        Closeable bound = null;
        Exception failure = null;
        try {
            bound = binder.bind(listener.address, listener.port, listener.router, listener.usm);
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
            bound = binder.bind(listener.address, listener.port, listener.router, listener.usm);
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
        for (ScheduledFuture<?> refresh : listener.refreshes.values()) {
            refresh.cancel(false);
        }
        listener.refreshes.clear();
        listener.router.clear();
        listener.router.close();
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
        volatile TrapListenerState state = TrapListenerState.FAILED;
        volatile String lastError;
        Closeable bound;
        ScheduledFuture<?> retry;
        final List<Runnable> watchers = new ArrayList<>();
        final NotificationRouter router;
        final Map<TrapRecipient, ScheduledFuture<?>> refreshes = new HashMap<>();
        final TrapUsmStack usm;
        /** USM recipients and their watchers; registry lock. */
        final Map<TrapRecipient, Runnable> v3Recipients = new LinkedHashMap<>();
        final Set<TrapRecipient> v3Registered = new HashSet<>();
        /** Read lock-free by diagnostics. */
        final Map<TrapRecipient, String> conflicts = new ConcurrentHashMap<>();

        Listener(int port, InetAddress address, Supplier<LicenseGate.Verdict> license, TrapUsmStack usm) {
            this.port = port;
            this.address = address;
            this.usm = usm;
            this.router = new NotificationRouter(port, license);
        }

        String endpoint() {
            return address.getHostAddress() + ":" + port;
        }
    }
}
