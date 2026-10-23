package com.jw.snmpdriver.snmp.trap;

import java.util.function.Consumer;

/**
 * What a Device registers on a Trap Listener. {@code sink} runs on the listener's worker for every
 * Notification that passed License, rate limit and queue;
 * {@code notice} receives operator-facing text for Traps/LastError. When {@code acceptInform} is false an
 * Inform is only counted as received: it is neither queued nor ACKed. A non-null {@code v3User} makes this a
 * USM Device: it accepts only v3 messages for that user, and never v1/v2c.
 */
public record TrapRecipient(
        String deviceName,
        TrapFilter filter,
        TrapCounters counters,
        TokenBucket limiter,
        Consumer<TrapNotification> sink,
        Consumer<String> notice,
        boolean acceptInform,
        TrapV3User v3User
) {
    /** A COMMUNITY Device. */
    public TrapRecipient(String deviceName, TrapFilter filter, TrapCounters counters, TokenBucket limiter,
                         Consumer<TrapNotification> sink, Consumer<String> notice, boolean acceptInform) {
        this(deviceName, filter, counters, limiter, sink, notice, acceptInform, null);
    }
}
