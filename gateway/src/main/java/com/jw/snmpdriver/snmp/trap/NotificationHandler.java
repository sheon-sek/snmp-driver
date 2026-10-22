package com.jw.snmpdriver.snmp.trap;

/** Receives every v1/v2c Notification PDU on a bound listener; runs on the transport thread. */
@FunctionalInterface
public interface NotificationHandler {
    void onNotification(IncomingNotification notification);
}
