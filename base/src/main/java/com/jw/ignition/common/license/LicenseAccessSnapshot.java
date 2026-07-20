package com.jw.ignition.common.license;

import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;

import java.util.List;

public record LicenseAccessSnapshot(
        boolean licensed,
        LicenseStatusCode code,
        String message,
        String verifiedAt,
        String expiresAt,
        List<String> features
) {
    public LicenseAccessSnapshot {
        features = features == null ? List.of() : List.copyOf(features);
    }

    public static LicenseAccessSnapshot from(LicenseStatusModel status) {
        if (status == null) {
            return new LicenseAccessSnapshot(false, null, "License status is unavailable.", "", "", List.of());
        }
        return new LicenseAccessSnapshot(
                status.licensed,
                status.code,
                value(status.message),
                value(status.verifiedAt),
                value(status.expiresAt),
                status.features
        );
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
