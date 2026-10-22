package com.jw.snmpdriver.snmp.trap;

import java.util.concurrent.atomic.AtomicLong;

/** Per-Device counters maintained by the router. */
public final class TrapCounters {
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong authFailure = new AtomicLong();

    /** Notifications routed to this Device after authentication. */
    public long received() {
        return received.get();
    }

    /** Source belongs to this Device but the community did not match. */
    public long authFailure() {
        return authFailure.get();
    }

    void countReceived() {
        received.incrementAndGet();
    }

    void countAuthFailure() {
        authFailure.incrementAndGet();
    }
}
