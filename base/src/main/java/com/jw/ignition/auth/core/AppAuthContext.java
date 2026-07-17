package com.jw.ignition.auth.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class AppAuthContext {
    private final AppAuthSource source;
    private final String username;
    private final Set<String> roles;
    private final Set<String> securityLevels;
    private final Set<String> claims;
    private final Set<String> authorities;

    public AppAuthContext(
            AppAuthSource source,
            String username,
            Collection<String> roles,
            Collection<String> securityLevels,
            Collection<String> claims
    ) {
        this.source = Objects.requireNonNull(source, "source");
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty()) {
            throw new IllegalArgumentException("username cannot be blank");
        }
        this.username = normalizedUsername;
        this.roles = clean(roles);
        this.securityLevels = clean(securityLevels);
        this.claims = clean(claims);
        this.authorities = buildAuthorities(this.roles, this.securityLevels, this.claims);
    }

    public AppAuthSource source() { return source; }
    public String username() { return username; }
    public Set<String> roles() { return roles; }
    public Set<String> securityLevels() { return securityLevels; }
    public Set<String> claims() { return claims; }
    public Set<String> authorities() { return authorities; }

    public boolean isSuperAdmin(AppAuthConfig config) {
        for (String role : config.getSuperAdminRoles()) {
            if (role != null && !role.isBlank() && authorities.contains(AuthCore.roleAuthority(role))) return true;
        }
        for (String level : config.getSuperAdminSecurityLevels()) {
            if (level != null && !level.isBlank() && authorities.contains("securityLevel:" + level.trim())) return true;
        }
        return false;
    }

    private static Set<String> clean(Collection<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) out.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> buildAuthorities(Set<String> roles, Set<String> levels, Set<String> claims) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String role : roles) out.add(AuthCore.roleAuthority(role));
        for (String level : levels) out.add("securityLevel:" + level);
        for (String claim : claims) out.add("claim:" + claim);
        return Collections.unmodifiableSet(out);
    }
}
