package com.jw.ignition.auth.core;

public record AppPermissionDef(String key, String label, String description) {
    public AppPermissionDef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("permission key cannot be blank");
        }
        key = key.trim();
        label = label == null || label.isBlank() ? key : label.trim();
        description = description == null ? "" : description.trim();
    }
}
