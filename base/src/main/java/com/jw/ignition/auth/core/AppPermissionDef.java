package com.jw.ignition.auth.core;

public final class AppPermissionDef {
    private final String key;
    private final String label;
    private final String description;

    public AppPermissionDef(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description == null ? "" : description;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String description() { return description; }
}
