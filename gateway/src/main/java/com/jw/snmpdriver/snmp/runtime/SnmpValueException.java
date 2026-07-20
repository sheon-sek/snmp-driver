package com.jw.snmpdriver.snmp.runtime;

public final class SnmpValueException extends IllegalArgumentException {
    private final long statusCode;
    private final String category;

    public SnmpValueException(long statusCode, String message) {
        this(statusCode, "value", message, null);
    }

    public SnmpValueException(long statusCode, String message, Throwable cause) {
        this(statusCode, "value", message, cause);
    }

    public SnmpValueException(long statusCode, String category, String message) {
        this(statusCode, category, message, null);
    }

    public SnmpValueException(
            long statusCode,
            String category,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
        this.category = category == null || category.isBlank() ? "value" : category;
    }

    public long statusCode() {
        return statusCode;
    }

    public String category() {
        return category;
    }
}

