package com.jw.ignition.auth.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppAuthConfigTest {

    @Test
    void allowsPermissionByRolePathAuthority() {
        AppAuthOptions options = new AppAuthOptions(
                "test",
                "authorization.manage",
                List.of(new AppPermissionDef("health.access", "Health", "View health"))
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
}
