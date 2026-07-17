package com.jw.ignition.license.exception;

import com.jw.ignition.license.model.LicenseStatusModel;

import java.util.List;

public final class LicenseFeatureAccessException extends RuntimeException {
    private final List<String> features;
    private final LicenseStatusModel status;

    public LicenseFeatureAccessException(List<String> features, LicenseStatusModel status) {
        super("License does not include any required feature: " + String.join(", ", features) + ".");
        this.features = List.copyOf(features);
        this.status = status;
    }

    public List<String> features() {
        return features;
    }

    public LicenseStatusModel status() {
        return status;
    }
}
