package com.jw.ignition.license.exception;

public final class LicenseUploadException extends RuntimeException {
    private final int status;
    private final String code;

    public LicenseUploadException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
