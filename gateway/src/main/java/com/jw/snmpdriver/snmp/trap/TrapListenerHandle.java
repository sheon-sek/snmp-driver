package com.jw.snmpdriver.snmp.trap;

/** One Device's reference to a Trap Listener, or the reason it was refused. */
public final class TrapListenerHandle {
    private final TrapListenerRegistry registry;
    private final TrapListenerRegistry.Listener listener;
    private final String rejection;
    private final String deviceName;
    private final Runnable onChange;
    private final TrapRecipient recipient;
    private final java.util.concurrent.ScheduledFuture<?> sourceRefresh;
    private boolean released;

    TrapListenerHandle(TrapListenerRegistry registry, TrapListenerRegistry.Listener listener, String rejection,
                       String deviceName, Runnable onChange, TrapRecipient recipient,
                       java.util.concurrent.ScheduledFuture<?> sourceRefresh) {
        this.registry = registry;
        this.listener = listener;
        this.rejection = rejection;
        this.deviceName = deviceName;
        this.onChange = onChange;
        this.recipient = recipient;
        this.sourceRefresh = sourceRefresh;
    }

    public boolean isRejected() {
        return rejection != null;
    }

    /** Conflict or validation reason; null when accepted. */
    public String rejection() {
        return rejection;
    }

    public TrapListenerState state() {
        return listener == null ? TrapListenerState.CLOSED : registry.stateOf(listener);
    }

    /** Last bind error; null while bound. */
    public String lastError() {
        return listener == null ? null : registry.lastErrorOf(listener);
    }

    /** e.g. {@code 0.0.0.0:10162}; null when rejected. */
    public String endpoint() {
        return listener == null ? null : listener.endpoint();
    }

    /** Listener-level: Notifications whose source belongs to no Device on this port. */
    public long unmatchedCount() {
        return listener == null ? 0 : registry.unmatchedCountOf(listener);
    }

    /** Listener-level: Notifications whose source matched more than one Device. */
    public long multiMatchCount() {
        return listener == null ? 0 : registry.multiMatchCountOf(listener);
    }

    public synchronized void release() {
        if (listener == null || released) {
            return;
        }
        released = true;
        registry.release(listener, deviceName, onChange, recipient, sourceRefresh);
    }
}
