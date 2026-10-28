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
    private final AtomicLong pendingInform = new AtomicLong();
    private final AtomicLong duplicateInform = new AtomicLong();

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

    /** Retransmitted Informs that were ACKed again without being queued. */
    public long duplicateInform() {
        return duplicateInform.get();
    }

    /** Cumulative PENDING Inform attempts: not stored or ACKed; each retransmission counts again. */
    public long pendingInform() {
        return pendingInform.get();
    }

    void countPendingInform() {
        pendingInform.incrementAndGet();
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

    void countDuplicateInform() {
        duplicateInform.incrementAndGet();
    }

    void countReceived() {
        received.incrementAndGet();
    }

    void countAuthFailure() {
        authFailure.incrementAndGet();
    }
}
