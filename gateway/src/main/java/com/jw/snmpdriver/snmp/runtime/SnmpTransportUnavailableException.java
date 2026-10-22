package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;

/** A required local transport is unavailable; no network attempt was made. */
public final class SnmpTransportUnavailableException extends IOException {
    public SnmpTransportUnavailableException(String message) {
        super(message);
    }
}
