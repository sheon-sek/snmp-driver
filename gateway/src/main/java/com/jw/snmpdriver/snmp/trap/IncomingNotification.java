package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;

import org.snmp4j.PDU;
import org.snmp4j.security.SecurityLevel;

/**
 * A decoded datagram as the transport saw it, before any routing. {@code community} is the v1/v2c community
 * or the v3 securityName; a v3 message has already passed USM, which reports {@code securityEngineId} (the
 * authoritative engine) and {@code securityLevel}. {@code ack} sends the Inform's RESPONSE and is null for
 * every other PDU type.
 */
public record IncomingNotification(
        InetAddress source,
        int sourcePort,
        TrapNotification.Version version,
        byte[] community,
        PDU pdu,
        Runnable ack,
        byte[] securityEngineId,
        int securityLevel
) {
    /** v1/v2c: no engine, community-based security. */
    public IncomingNotification(InetAddress source, int sourcePort, TrapNotification.Version version,
                                byte[] community, PDU pdu, Runnable ack) {
        this(source, sourcePort, version, community, pdu, ack, null, SecurityLevel.NOAUTH_NOPRIV);
    }
}
