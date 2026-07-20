package com.jw.ignition.license.api;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.jw.ignition.common.api.AbstractApiRouteRegistrar;
import com.jw.ignition.license.service.ModuleLicenseService;

public final class LicenseRouteRegistrar extends AbstractApiRouteRegistrar {
    private static final AccessControlStrategy WEB_UI_READ =
            new WebUiSession.WebUiSessionAccessControl(PermissionType.READ);
    private static final AccessControlStrategy WEB_UI_CSRF =
            new WebUiSession.CsrfWebUiSessionAccessControl(PermissionType.READ);

    private final LicenseRoutes licenseRoutes;

    public LicenseRouteRegistrar(
            ModuleLicenseService moduleLicenseService,
            String routePrefix,
            String moduleName,
            String mountAlias
    ) {
        super(routePrefix);
        this.licenseRoutes = new LicenseRoutes(moduleLicenseService, moduleName, mountAlias);
    }

    @Override
    public void register(RouteGroup routes) {
        html(routes, WEB_UI_READ, HttpMethod.GET, "/license/page", licenseRoutes::page);
        json(routes, WEB_UI_READ, HttpMethod.GET, "/license/status", licenseRoutes::status);
        binary(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/license/machine-request",
                licenseRoutes::machineRequest
        );
        json(routes, WEB_UI_CSRF, HttpMethod.POST, "/license/upload", licenseRoutes::upload);
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/license/revalidate",
                licenseRoutes::revalidate
        );
    }
}
