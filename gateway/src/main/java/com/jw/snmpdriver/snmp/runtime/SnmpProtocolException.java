package com.jw.snmpdriver.snmp.runtime;

import org.snmp4j.PDU;

public final class SnmpProtocolException extends RuntimeException {
    private final int errorStatus;
    private final int errorIndex;

    public SnmpProtocolException(int errorStatus, int errorIndex, String message) {
        super(buildMessage(errorStatus, errorIndex, message));
        this.errorStatus = errorStatus;
        this.errorIndex = errorIndex;
    }

    public int errorStatus() {
        return errorStatus;
    }

    public int errorIndex() {
        return errorIndex;
    }

    private static String buildMessage(int errorStatus, int errorIndex, String detail) {
        String statusText = PDU.toErrorStatusText(errorStatus);
        String normalizedDetail = detail == null ? "" : detail.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalizedDetail.length() > 1_024) {
            normalizedDetail = normalizedDetail.substring(0, 1_024) + "...";
        }
        String prefix = "SNMP protocol error status=" + statusText
                + "(" + errorStatus + ") errorIndex=" + errorIndex;
        return normalizedDetail.isBlank() || normalizedDetail.equalsIgnoreCase(statusText)
                ? prefix
                : prefix + " detail=" + normalizedDetail;
    }
}

