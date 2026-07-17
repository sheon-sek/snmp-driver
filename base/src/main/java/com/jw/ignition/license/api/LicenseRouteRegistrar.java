package com.jw.ignition.license.api;

import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.api.AbstractApiRouteRegistrar;
import com.jw.ignition.license.service.ModuleLicenseService;

public final class LicenseRouteRegistrar extends AbstractApiRouteRegistrar {
    private final LicenseRoutes licenseRoutes;
    private final String managePermissionKey;

    public LicenseRouteRegistrar(
        GatewayContext context,
        AuthCore auth,
        ModuleLicenseService moduleLicenseService,
        String routePrefix,
        String moduleName,
        String mountAlias,
        String managePermissionKey
    ) {
        super(context, auth, routePrefix);
        this.licenseRoutes = new LicenseRoutes(moduleLicenseService, moduleName, mountAlias);
        this.managePermissionKey = managePermissionKey;
    }

    @Override
    public void register(RouteGroup routes) {
        html(routes, HttpMethod.GET, "/license/page", true, managePermissionKey, licenseRoutes::page);
        json(routes, HttpMethod.GET, "/license/status", true, "", licenseRoutes::status);
        binary(routes, HttpMethod.GET, "/license/machine-request", true, managePermissionKey, licenseRoutes::machineRequest);
        json(routes, HttpMethod.POST, "/license/upload", true, managePermissionKey, licenseRoutes::upload);
        json(routes, HttpMethod.POST, "/license/revalidate", true, managePermissionKey, licenseRoutes::revalidate);
    }
}
