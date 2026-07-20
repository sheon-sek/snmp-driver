package com.jw.ignition.common.license;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record LicenseRequirement(Match match, List<String> features) {
    public enum Match {
        LICENSED,
        ALL,
        ANY
    }

    public LicenseRequirement {
        if (match == null) {
            throw new IllegalArgumentException("License requirement match is required.");
        }

        List<String> normalized = normalizeFeatures(features);
        if (match == Match.LICENSED && !normalized.isEmpty()) {
            throw new IllegalArgumentException("Licensed-only requirement cannot declare features.");
        }
        if (match != Match.LICENSED && normalized.isEmpty()) {
            throw new IllegalArgumentException("Feature requirement must declare at least one feature.");
        }
        features = normalized;
    }

    public static LicenseRequirement licensed() {
        return new LicenseRequirement(Match.LICENSED, List.of());
    }

    public static LicenseRequirement allFeatures(String... features) {
        return new LicenseRequirement(Match.ALL, toList(features));
    }

    public static LicenseRequirement anyFeatures(String... features) {
        return new LicenseRequirement(Match.ANY, toList(features));
    }

    public String wireMatch() {
        return match.name().toLowerCase(Locale.ROOT);
    }

    private static List<String> toList(String[] features) {
        if (features == null) {
            return List.of();
        }
        return Arrays.asList(features);
    }

    private static List<String> normalizeFeatures(List<String> features) {
        List<String> normalized = new ArrayList<>();
        if (features == null) {
            return List.of();
        }
        for (String feature : features) {
            if (feature == null || feature.trim().isBlank()) {
                throw new IllegalArgumentException("License feature key is required.");
            }
            String value = feature.trim().toLowerCase(Locale.ROOT);
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }
}
