package com.jw.snmpdriver.snmp.trap;

import java.util.function.Consumer;

/**
 * What a Device registers on a Trap Listener. {@code sink} runs on the listener's worker for every
 * Notification that passed License, rate limit and queue;
 * {@code notice} receives operator-facing text for Traps/LastError. When {@code acceptInform} is false an
 * Inform is only counted as received: it is neither queued nor ACKed.
 */
public record TrapRecipient(
        String deviceName,
        TrapFilter filter,
        TrapCounters counters,
        TokenBucket limiter,
        Consumer<TrapNotification> sink,
        Consumer<String> notice,
        boolean acceptInform
) {
}
