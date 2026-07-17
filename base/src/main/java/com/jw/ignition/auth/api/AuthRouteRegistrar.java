package com.jw.ignition.auth.api;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.api.AbstractApiRouteRegistrar;

public final class AuthRouteRegistrar extends AbstractApiRouteRegistrar {
    private static final AccessControlStrategy WEB_UI_READ =
            new WebUiSession.WebUiSessionAccessControl(PermissionType.READ);
    private static final AccessControlStrategy WEB_UI_CSRF =
            new WebUiSession.CsrfWebUiSessionAccessControl(PermissionType.READ);

    private final AuthRoutes authRoutes;

    public AuthRouteRegistrar(GatewayContext context, AuthCore auth, String routePrefix) {
        super(context, auth, routePrefix);
        this.authRoutes = new AuthRoutes(auth);
    }

    @Override
    public void register(RouteGroup routes) {
        json(routes, HttpMethod.GET, "/auth/me", true, "", authRoutes::handleAppAuthMe);

        String managePermission = auth.options().authorizationManagePermissionKey();

        json(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/authorization/config",
                true,
                managePermission,
                authRoutes::handleAppAuthConfig
        );
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/authorization/config",
                true,
                managePermission,
                authRoutes::handleUpdateConfig
        );
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/authorization/config/reset",
                true,
                managePermission,
                authRoutes::handleResetConfig
        );
        json(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/authorization/security-level-roles",
                true,
                managePermission,
                authRoutes::handleSecurityLevelRoles
        );
    }
}
