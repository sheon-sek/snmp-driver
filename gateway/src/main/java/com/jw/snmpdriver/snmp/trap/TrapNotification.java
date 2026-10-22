package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import org.snmp4j.smi.VariableBinding;

/**
 * One accepted Notification in v2 form. {@code varbinds} always starts with sysUpTime.0 and snmpTrapOID.0;
 * a v1 Trap has been converted per RFC 3584 §3.1.
 */
public record TrapNotification(
        Instant receivedAt,
        Version version,
        PduType pduType,
        InetAddress source,
        int sourcePort,
        String agentAddress,
        long agentUpTime,
        String trapOid,
        List<VariableBinding> varbinds
) {
    public enum Version { V1, V2C, V3 }

    public enum PduType { V1_TRAP, V2_TRAP, INFORM }
}
