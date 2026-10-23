package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;

/** Receives every Notification PDU on a bound listener; runs on the transport thread. */
@FunctionalInterface
public interface NotificationHandler {
    void onNotification(IncomingNotification notification);

    /**
     * A v3 message from {@code source} that USM rejected before any PDU existed; {@code status} is the SNMP4J
     * error code (for example {@code SNMPv3_USM_AUTHENTICATION_FAILURE}).
     */
    default void onAuthenticationFailure(InetAddress source, int status) {
    }

    /** As above; {@code toLocalEngine} when the message claimed the Gateway engine, i.e. was a v3 Inform. */
    default void onAuthenticationFailure(InetAddress source, int status, boolean toLocalEngine) {
        onAuthenticationFailure(source, status);
    }
}
