package com.jw.snmpdriver.snmp.runtime;

import java.util.concurrent.TimeoutException;

/** Local queue deadline expiration; it does not prove remote communication failure. */
public final class SnmpQueueTimeoutException extends TimeoutException {
    public SnmpQueueTimeoutException(String message) {
        super(message);
    }
}

