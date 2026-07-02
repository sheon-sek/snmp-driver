package com.jw.ignition.auth.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AppAuthOptions {
    private final String namespace;
    private final String authorizationManagePermissionKey;
    private final List<AppPermissionDef> permissions;
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
        this.namespace = namespace;
        this.authorizationManagePermissionKey = authorizationManagePermissionKey;
        this.permissions = List.copyOf(permissions);
        this.superAdminRoles = Set.copyOf(superAdminRoles);
        this.superAdminSecurityLevels = Set.copyOf(superAdminSecurityLevels);
    }

    public String namespace() { return namespace; }
    public String authorizationManagePermissionKey() { return authorizationManagePermissionKey; }
    public List<AppPermissionDef> permissions() { return permissions; }
    public Set<String> superAdminRoles() { return superAdminRoles; }
    public Set<String> superAdminSecurityLevels() { return superAdminSecurityLevels; }

    public Set<String> permissionKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for(AppPermissionDef def : permissions) keys.add(def.key());
        return keys;
    }
}
