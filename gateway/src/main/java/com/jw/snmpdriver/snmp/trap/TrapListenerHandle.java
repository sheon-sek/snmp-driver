package com.jw.snmpdriver.snmp.trap;

/** One Device's reference to a Trap Listener, or the reason it was refused. */
public final class TrapListenerHandle {
    private final TrapListenerRegistry registry;
    private final TrapListenerRegistry.Listener listener;
    private final String rejection;
    private final String deviceName;
    private final Runnable onChange;
    private boolean released;

    TrapListenerHandle(TrapListenerRegistry registry, TrapListenerRegistry.Listener listener, String rejection,
                       String deviceName, Runnable onChange) {
        this.registry = registry;
        this.listener = listener;
        this.rejection = rejection;
        this.deviceName = deviceName;
        this.onChange = onChange;
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

    public synchronized void release() {
        if (listener == null || released) {
            return;
        }
        released = true;
        registry.release(listener, deviceName, onChange);
    }
}
