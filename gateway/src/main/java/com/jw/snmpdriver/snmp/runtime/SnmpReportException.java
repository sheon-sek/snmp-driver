package com.jw.snmpdriver.snmp.runtime;

/** A non-USM SNMPv3 REPORT with an explicit manager-side classification. */
public final class SnmpReportException extends RuntimeException {
    private final String category;
    private final long statusCode;
    private final boolean circuitFailure;
    private final boolean legalResponse;

    public SnmpReportException(
            String category,
            long statusCode,
            boolean circuitFailure,
            boolean legalResponse,
            String message
    ) {
        super(message);
        this.category = category;
        this.statusCode = statusCode;
        this.circuitFailure = circuitFailure;
        this.legalResponse = legalResponse;
    }

    public String category() {
        return category;
    }

    public long statusCode() {
        return statusCode;
    }

    public boolean circuitFailure() {
        return circuitFailure;
    }

    public boolean legalResponse() {
        return legalResponse;
    }
}

