package com.jw.snmpdriver.snmp.trap;

import java.util.concurrent.atomic.AtomicLong;

/** Per-Device counters maintained by the router and its worker. */
public final class TrapCounters {
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong authFailure = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong droppedRateLimit = new AtomicLong();
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong droppedUnlicensed = new AtomicLong();

    /** Notifications routed to this Device after authentication. */
    public long received() {
        return received.get();
    }

    /** Source belongs to this Device but the community did not match. */
    public long authFailure() {
        return authFailure.get();
    }

    /** Dequeued and handed to the Device successfully. */
    public long accepted() {
        return accepted.get();
    }

    public long droppedRateLimit() {
        return droppedRateLimit.get();
    }

    public long droppedQueueFull() {
        return droppedQueueFull.get();
    }

    public long droppedUnlicensed() {
        return droppedUnlicensed.get();
    }

    void countAccepted() {
        accepted.incrementAndGet();
    }

    void countDroppedRateLimit() {
        droppedRateLimit.incrementAndGet();
    }

    void countDroppedQueueFull() {
        droppedQueueFull.incrementAndGet();
    }

    void countDroppedUnlicensed() {
        droppedUnlicensed.incrementAndGet();
    }

    void countReceived() {
        received.incrementAndGet();
    }

    void countAuthFailure() {
        authFailure.incrementAndGet();
    }
}
