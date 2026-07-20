package com.jw.ignitionsdkboilerplate.gateway.auth;

import com.jw.ignition.auth.core.AppPermissionDef;

import java.util.List;

public final class AppPermission {
    public static final String AUTHORIZATION_MANAGE = "authorization.manage";
    public static final String LICENSE_MANAGE = "license.manage";
    public static final String HEALTH_ACCESS = "health.access";
    private static final List<AppPermissionDef> CATALOG = List.of(
            new AppPermissionDef(
                    AUTHORIZATION_MANAGE,
                    "Authorization:Manage",
                    "Edit module role/security-level permissions"
            ),
            new AppPermissionDef(
                    LICENSE_MANAGE,
                    "License:Manage",
                    "View status, upload, and revalidate the module license"
            ),
            new AppPermissionDef(HEALTH_ACCESS, "Health:Access", "View health status")
    );

    public static List<AppPermissionDef> catalog() {
        return CATALOG;
    }

    private AppPermission() {
    }
}
