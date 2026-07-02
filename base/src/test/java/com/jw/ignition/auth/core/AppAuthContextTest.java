package com.jw.ignition.auth.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppAuthContextTest {

    @Test
    void authoritiesUseSecurityLevelRolePaths() {
        AppAuthContext actor = new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                "alice",
                List.of("Authenticated/Roles/Engineer"),
                List.of("Authenticated"),
                List.of("canEdit")
        );

        assertEquals("alice", actor.username());
        assertTrue(actor.roles().contains("Authenticated/Roles/Engineer"));
        assertTrue(actor.authorities().contains("role:Authenticated/Roles/Engineer"));
        assertTrue(actor.authorities().contains("securityLevel:Authenticated"));
        assertTrue(actor.authorities().contains("claim:canEdit"));
    }
}
