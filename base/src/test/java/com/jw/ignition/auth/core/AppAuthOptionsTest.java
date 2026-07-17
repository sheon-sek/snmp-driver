package com.jw.ignition.auth.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AppAuthOptionsTest {

    @Test
    void rejectsMissingAuthorizationManagementPermission() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppAuthOptions(
                        "test",
                        "authorization.manage",
                        List.of(new AppPermissionDef("health.access", "Health", "View health"))
                )
        );
    }

    @Test
    void rejectsDuplicatePermissionKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppAuthOptions(
                        "test",
                        "authorization.manage",
                        List.of(
                                new AppPermissionDef("authorization.manage", "Manage", "First"),
                                new AppPermissionDef("authorization.manage", "Manage", "Duplicate")
                        )
                )
        );
    }
}
