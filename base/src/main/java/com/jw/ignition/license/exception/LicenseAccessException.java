package com.jw.ignition.license.exception;

import com.jw.ignition.license.model.LicenseStatusModel;

public final class LicenseAccessException extends RuntimeException {
    private final LicenseStatusModel status;

    public LicenseAccessException(LicenseStatusModel status) {
        super(status == null || status.message == null || status.message.isBlank()
            ? "Module is not licensed."
            : status.message);
        this.status = status;
    }

    public LicenseStatusModel status() {
        return status;
    }
}
