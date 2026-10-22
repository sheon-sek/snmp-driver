package com.jw.snmpdriver.snmp.trap;

import java.util.function.Consumer;

/**
 * What a Device registers on a Trap Listener. {@code sink} receives authenticated Notifications;
 * {@code notice} receives operator-facing text for Traps/LastError.
 */
public record TrapRecipient(
        String deviceName,
        TrapFilter filter,
        TrapCounters counters,
        Consumer<TrapNotification> sink,
        Consumer<String> notice
) {
}
