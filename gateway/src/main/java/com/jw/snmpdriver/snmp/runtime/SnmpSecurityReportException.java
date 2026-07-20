package com.jw.snmpdriver.snmp.runtime;

/** A remote SNMPv3 REPORT supplied a concrete USM/security failure reason. */
public final class SnmpSecurityReportException extends SecurityException {
    public SnmpSecurityReportException(String message) {
        super(message);
    }
}

