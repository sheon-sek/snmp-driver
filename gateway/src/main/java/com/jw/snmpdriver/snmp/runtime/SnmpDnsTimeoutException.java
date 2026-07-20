package com.jw.snmpdriver.snmp.runtime;

import java.util.concurrent.TimeoutException;

/** Local DNS lookup exceeded its bounded resolver deadline. */
public final class SnmpDnsTimeoutException extends TimeoutException {
    public SnmpDnsTimeoutException(String message) {
        super(message);
    }
}

