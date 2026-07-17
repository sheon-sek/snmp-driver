package com.jw.ignition.auth.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppAuthConfigTest {

    @Test
    void allowsPermissionByRolePathAuthority() {
        AppAuthOptions options = new AppAuthOptions(
                "test",
                "authorization.manage",
                permissionCatalog()
        );

        AppAuthConfig config = AppAuthConfig.defaultConfig(options);
        Map<String, Set<String>> permissions = new LinkedHashMap<>();
        permissions.put("health.access", new LinkedHashSet<>(List.of("role:Authenticated/Roles/Engineer")));
        permissions.put("authorization.manage", new LinkedHashSet<>());
        config.setPermissions(permissions);
        config.normalize(options);

        AppAuthContext engineer = new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                "alice",
                List.of("Authenticated/Roles/Engineer"),
                List.of(),
                List.of()
        );

        AppAuthContext operator = new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                "bob",
                List.of("Authenticated/Roles/Operator"),
                List.of(),
                List.of()
        );

        assertTrue(config.isAllowed(engineer, "health.access"));
        assertFalse(config.isAllowed(operator, "health.access"));
    }

    @Test
    void copyDoesNotShareMutableCollections() {
        AppAuthOptions options = new AppAuthOptions(
                "test",
                "authorization.manage",
                permissionCatalog()
        );
        AppAuthConfig original = AppAuthConfig.defaultConfig(options);
        AppAuthConfig copy = original.copy();

        assertNotSame(original.getSuperAdminRoles(), copy.getSuperAdminRoles());
        assertNotSame(original.getPermissions(), copy.getPermissions());

        copy.getSuperAdminRoles().add("Authenticated/Roles/Engineer");
        copy.getPermissions().get("health.access").add("role:Authenticated/Roles/Engineer");

        assertFalse(original.getSuperAdminRoles().contains("Authenticated/Roles/Engineer"));
        assertFalse(original.getPermissions().get("health.access")
                .contains("role:Authenticated/Roles/Engineer"));
    }

    @Test
    void normalizeAcceptsImmutableInputCollections() {
        AppAuthOptions options = new AppAuthOptions(
                "test",
                "authorization.manage",
                permissionCatalog()
        );
        AppAuthConfig config = new AppAuthConfig();
        config.setSuperAdminRoles(Set.of("Administrator"));
        config.setSuperAdminSecurityLevels(Set.of("Authenticated"));
        config.setPermissions(Map.of(
                "health.access",
                Set.of("role:Engineer")
        ));

        config.normalize(options);

        assertTrue(config.getSuperAdminRoles().contains("Authenticated/Roles/Administrator"));
        assertTrue(config.getPermissions().get("health.access")
                .contains("role:Authenticated/Roles/Engineer"));
    }

    private static List<AppPermissionDef> permissionCatalog() {
        return List.of(
                new AppPermissionDef("authorization.manage", "Authorization", "Manage authorization"),
                new AppPermissionDef("health.access", "Health", "View health")
        );
    }
}
