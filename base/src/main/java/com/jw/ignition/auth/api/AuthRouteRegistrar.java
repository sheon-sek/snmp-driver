package com.jw.ignition.auth.api;

import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.api.AbstractApiRouteRegistrar;

public final class AuthRouteRegistrar extends AbstractApiRouteRegistrar {
    private final AuthRoutes authRoutes;

    public AuthRouteRegistrar(GatewayContext context, AuthCore auth, String routePrefix) {
        super(context, auth, routePrefix);
        this.authRoutes = new AuthRoutes(auth);
    }

    @Override
    public void register(RouteGroup routes) {
        json(routes, HttpMethod.GET, "/auth/me", true, "", authRoutes::handleAppAuthMe);

        json(routes, HttpMethod.GET, "/authorization/config", true, auth.options().authorizationManagePermissionKey(), authRoutes::handleAppAuthConfig);
        json(routes, HttpMethod.POST, "/authorization/config", true, auth.options().authorizationManagePermissionKey(), authRoutes::handleUpdateConfig);
        json(routes, HttpMethod.POST, "/authorization/config/reset", true, auth.options().authorizationManagePermissionKey(), authRoutes::handleResetConfig);
        json(
                routes,
                HttpMethod.GET,
                "/authorization/security-level-roles",
                true,
                auth.options().authorizationManagePermissionKey(),
                authRoutes::handleSecurityLevelRoles
        );
    }
}
