package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;

import org.snmp4j.PDU;

/** A decoded v1/v2c datagram as the transport saw it, before any routing or authentication. */
public record IncomingNotification(
        InetAddress source,
        int sourcePort,
        TrapNotification.Version version,
        byte[] community,
        PDU pdu
) {
}
