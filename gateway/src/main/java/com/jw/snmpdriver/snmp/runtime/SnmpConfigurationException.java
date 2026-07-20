package com.jw.snmpdriver.snmp.runtime;

/** A validated Device setting or resolved credential is unusable at runtime. */
public final class SnmpConfigurationException extends IllegalArgumentException {
    public SnmpConfigurationException(String message) {
        super(message);
    }
}

