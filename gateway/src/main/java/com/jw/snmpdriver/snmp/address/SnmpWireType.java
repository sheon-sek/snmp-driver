package com.jw.snmpdriver.snmp.address;

import java.util.Arrays;

import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

public enum SnmpWireType {
    INTEGER32("Integer32", Identifiers.Int32),
    UNSIGNED32("Unsigned32", Identifiers.UInt32),
    COUNTER32("Counter32", Identifiers.UInt32),
    GAUGE32("Gauge32", Identifiers.UInt32),
    TIMETICKS("TimeTicks", Identifiers.UInt32),
    COUNTER64("Counter64", Identifiers.UInt64),
    OCTET_STRING("OctetString", Identifiers.String),
    BYTE_STRING("ByteString", Identifiers.ByteString),
    OBJECT_IDENTIFIER("ObjectIdentifier", Identifiers.String),
    IP_ADDRESS("IpAddress", Identifiers.String),
    OPAQUE("Opaque", Identifiers.ByteString),
    OPAQUE_FLOAT("OpaqueFloat", Identifiers.Float),
    OPAQUE_DOUBLE("OpaqueDouble", Identifiers.Double),
    STRING_FALLBACK("String", Identifiers.String);

    private final String canonicalToken;
    private final NodeId dataType;

    SnmpWireType(String canonicalToken, NodeId dataType) {
        this.canonicalToken = canonicalToken;
        this.dataType = dataType;
    }

    public String canonicalToken() {
        return canonicalToken;
    }

    public NodeId dataType() {
        return dataType;
    }

    public static SnmpWireType fromToken(String token) {
        return Arrays.stream(values())
                .filter(value -> value != STRING_FALLBACK)
                .filter(value -> value.canonicalToken.equalsIgnoreCase(token))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported SNMP type '" + token
                                + "'; supported types: Integer32, Unsigned32, Counter32, Gauge32, TimeTicks, "
                                + "Counter64, OctetString, ByteString, ObjectIdentifier, IpAddress, Opaque, "
                                + "OpaqueFloat, OpaqueDouble"
                ));
    }
}

