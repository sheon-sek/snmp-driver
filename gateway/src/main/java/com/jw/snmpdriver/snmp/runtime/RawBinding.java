package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;

import com.jw.snmpdriver.snmp.address.NumericOid;
import org.snmp4j.smi.Variable;

public record RawBinding(NumericOid oid, int syntax, Variable value, Instant receivedAt) {
}

