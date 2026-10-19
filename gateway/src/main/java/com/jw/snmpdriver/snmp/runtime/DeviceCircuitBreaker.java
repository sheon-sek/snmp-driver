package com.jw.snmpdriver.snmp.runtime;

import java.util.concurrent.TimeUnit;

public final class DeviceCircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final long[] BACKOFF_MS = {2_000, 4_000, 8_000, 16_000, 32_000, 60_000};

    private State state = State.CLOSED;
    private int consecutiveTransportFailures;
    private int backoffIndex;
    private long nextProbeNanos;

    public synchronized boolean allowNormal(long nowNanos) {
        return state == State.CLOSED;
    }

    public synchronized boolean tryHalfOpen(long nowNanos) {
        if (state != State.OPEN || nowNanos < nextProbeNanos) {
            return false;
        }
        state = State.HALF_OPEN;
        return true;
    }

    public synchronized void onValidResponse() {
        state = State.CLOSED;
        consecutiveTransportFailures = 0;
        backoffIndex = 0;
    }

    /** Retry a probe that did not establish a network outcome without escalating backoff. */
    public synchronized void abortProbe(long nowNanos) {
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            nextProbeNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(BACKOFF_MS[0]);
        }
    }

    public synchronized void onTransportFailure(long nowNanos, long jitterMillis) {
        consecutiveTransportFailures++;
        if (state == State.HALF_OPEN || consecutiveTransportFailures >= 2) {
            state = State.OPEN;
            long delay = BACKOFF_MS[Math.min(backoffIndex, BACKOFF_MS.length - 1)] + jitterMillis;
            nextProbeNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(delay);
            backoffIndex = Math.min(backoffIndex + 1, BACKOFF_MS.length - 1);
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long nanosUntilProbe(long nowNanos) {
        if (state != State.OPEN) {
            return 0L;
        }
        return Math.max(0L, nextProbeNanos - nowNanos);
    }
}

