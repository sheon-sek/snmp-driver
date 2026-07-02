package com.jw.ignition.auth.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class AppAuthConfig {
    private int version = 1;
    private int revision = 0;
    private Set<String> superAdminRoles = new LinkedHashSet<>();
    private Set<String> superAdminSecurityLevels = new LinkedHashSet<>();
    private Map<String, Set<String>> permissions = new LinkedHashMap<>();

    public static AppAuthConfig defaultConfig(AppAuthOptions options) {
        AppAuthConfig c = new AppAuthConfig();
        c.superAdminRoles.addAll(options.superAdminRoles());
        c.superAdminSecurityLevels.addAll(options.superAdminSecurityLevels());
        Set<String> admins = adminAuthorities(options);
        for (String key : options.permissionKeys()) {
            c.permissions.put(key, new LinkedHashSet<>(admins));
        }
        c.normalize(options);
        return c;
    }

    public void normalize(AppAuthOptions options) {
        if(superAdminRoles == null) superAdminRoles = new LinkedHashSet<>();
        if(superAdminSecurityLevels == null) superAdminSecurityLevels = new LinkedHashSet<>();
        if(permissions == null) permissions = new LinkedHashMap<>();

        superAdminRoles.addAll(options.superAdminRoles());
        superAdminSecurityLevels.addAll(options.superAdminSecurityLevels());
        superAdminRoles = normalizeRolePaths(superAdminRoles);
        superAdminSecurityLevels = cleanSet(superAdminSecurityLevels);

        Set<String> admins = adminAuthorities(options);
        for (String key : options.permissionKeys()) {
            permissions.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        }

        permissions.computeIfAbsent(options.authorizationManagePermissionKey(), ignored -> new LinkedHashSet<>());
        permissions.get(options.authorizationManagePermissionKey()).addAll(admins);

        // Drop unknown permission keys. This makes old configs safe after code changes.
        permissions.keySet().removeIf(key -> !options.permissionKeys().contains(key));

        for (Map.Entry<String, Set<String>> entry : permissions.entrySet()) {
            entry.setValue(normalizeAuthorities(entry.getValue()));
        }
    }

    public void validate(AppAuthOptions options) {
        for (String key : permissions.keySet()) {
            if(!options.permissionKeys().contains(key)) {
                throw new IllegalArgumentException("Unknown permission key: " + key);
            }
        }
    }

    public boolean isAllowed(AppAuthContext user, String permissionKey) {
        if (user.isSuperAdmin(this)) return true;
        Set<String> allowed = permissions.getOrDefault(permissionKey, Set.of());
        for (String authority : user.authorities()) {
            if (allowed.contains(authority)) return true;
        }
        return false;
    }

    private static Set<String> adminAuthorities(AppAuthOptions options) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String role : options.superAdminRoles()) {
            if (role != null && !role.isBlank()) out.add(AuthCore.roleAuthority(role));
        }
        for (String level : options.superAdminSecurityLevels()) {
            if (level != null && !level.isBlank()) out.add("securityLevel:" + level.trim());
        }
        return out;
    }

    private static Set<String> normalizeRolePaths(Set<String> roles) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (roles == null) return out;
        for (String role : roles) {
            if (role != null && !role.isBlank()) out.add(AuthCore.securityLevelRolePath(role));
        }
        return out;
    }

    private static Set<String> cleanSet(Set<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim());
        }
        return out;
    }

    private static Set<String> normalizeAuthorities(Set<String> authorities) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (authorities == null) return out;
        for (String authority : authorities) {
            if (authority == null || authority.isBlank()) continue;
            String trimmed = authority.trim();
            if (trimmed.startsWith("role:")) {
                String role = trimmed.substring("role:".length()).trim();
                if (!role.isBlank()) out.add(AuthCore.roleAuthority(role));
            } else if (trimmed.startsWith("securityLevel:")) {
                String level = trimmed.substring("securityLevel:".length()).trim();
                if (!level.isBlank()) out.add("securityLevel:" + level);
            } else if (trimmed.startsWith("claim:")) {
                String claim = trimmed.substring("claim:".length()).trim();
                if (!claim.isBlank()) out.add("claim:" + claim);
            }
        }
        return out;
    }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public Set<String> getSuperAdminRoles() { return superAdminRoles == null ? Set.of() : superAdminRoles; }
    public void setSuperAdminRoles(Set<String> superAdminRoles) { this.superAdminRoles = superAdminRoles; }
    public Set<String> getSuperAdminSecurityLevels() { return superAdminSecurityLevels == null ? Set.of() : superAdminSecurityLevels; }
    public void setSuperAdminSecurityLevels(Set<String> superAdminSecurityLevels) { this.superAdminSecurityLevels = superAdminSecurityLevels; }
    public Map<String, Set<String>> getPermissions() { return permissions == null ? Map.of() : permissions; }
    public void setPermissions(Map<String, Set<String>> permissions) { this.permissions = permissions; }
}
