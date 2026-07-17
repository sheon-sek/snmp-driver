package com.jw.ignition.auth.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AppAuthOptions {
    private final String namespace;
    private final String authorizationManagePermissionKey;
    private final List<AppPermissionDef> permissions;
    private final Set<String> permissionKeys;
    private final Set<String> superAdminRoles;
    private final Set<String> superAdminSecurityLevels;

    public AppAuthOptions(
            String namespace,
            String authorizationManagePermissionKey,
            List<AppPermissionDef> permissions
    ) {
        this(
                namespace,
                authorizationManagePermissionKey,
                permissions,
                Set.of("Authenticated/Roles/Administrator"),
                Set.of("Authenticated/Roles/Administrator")
        );
    }

    public AppAuthOptions(
            String namespace,
            String authorizationManagePermissionKey,
            List<AppPermissionDef> permissions,
            Set<String> superAdminRoles,
            Set<String> superAdminSecurityLevels
    ) {
        this.namespace = requireNonBlank(namespace, "namespace");
        this.authorizationManagePermissionKey = requireNonBlank(
                authorizationManagePermissionKey,
                "authorizationManagePermissionKey"
        );
        this.permissions = List.copyOf(permissions);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (AppPermissionDef permission : this.permissions) {
            String key = requireNonBlank(permission.key(), "permission key");
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate permission key: " + key);
            }
        }
        if (!keys.contains(this.authorizationManagePermissionKey)) {
            throw new IllegalArgumentException(
                    "Permission catalog must contain authorization management key: "
                            + this.authorizationManagePermissionKey
            );
        }
        this.permissionKeys = Collections.unmodifiableSet(keys);
        this.superAdminRoles = Set.copyOf(
                Objects.requireNonNull(superAdminRoles, "superAdminRoles")
        );
        this.superAdminSecurityLevels = Set.copyOf(
                Objects.requireNonNull(superAdminSecurityLevels, "superAdminSecurityLevels")
        );
    }

    public String namespace() { return namespace; }
    public String authorizationManagePermissionKey() { return authorizationManagePermissionKey; }
    public List<AppPermissionDef> permissions() { return permissions; }
    public Set<String> superAdminRoles() { return superAdminRoles; }
    public Set<String> superAdminSecurityLevels() { return superAdminSecurityLevels; }

    public Set<String> permissionKeys() { return permissionKeys; }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
