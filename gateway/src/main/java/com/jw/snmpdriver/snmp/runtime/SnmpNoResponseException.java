package com.jw.snmpdriver.snmp.runtime;

import java.util.concurrent.TimeoutException;

/** Wire request exhausted retries without a response; the exact remote cause may be unknowable. */
public final class SnmpNoResponseException extends TimeoutException {
    public SnmpNoResponseException(String message) {
        super(message);
    }
}

