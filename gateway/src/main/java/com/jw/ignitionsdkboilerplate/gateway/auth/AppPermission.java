package com.jw.ignitionsdkboilerplate.gateway.auth;

import com.jw.ignition.auth.core.AppPermissionDef;

import java.util.List;

public final class AppPermission {
    public static final String AUTHORIZATION_MANAGE = "authorization.manage";
    public static final String HEALTH_ACCESS = "health.access";

    public static List<AppPermissionDef> catalog() {
        return List.of(
                new AppPermissionDef(AUTHORIZATION_MANAGE, "Authorization:Manage", "Edit module role/security-level permissions"),
                new AppPermissionDef(HEALTH_ACCESS, "Health:Access", "View health status")
        );
    }
}
