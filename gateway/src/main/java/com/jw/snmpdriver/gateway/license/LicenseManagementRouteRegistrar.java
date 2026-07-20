package com.jw.snmpdriver.gateway.license;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.jw.ignition.common.api.AbstractApiRouteRegistrar;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignition.license.api.LicenseRoutes;

public final class LicenseManagementRouteRegistrar extends AbstractApiRouteRegistrar {
    private static final AccessControlStrategy WEB_UI_READ =
            new WebUiSession.WebUiSessionAccessControl(PermissionType.READ);
    private static final AccessControlStrategy WEB_UI_CSRF =
            new WebUiSession.CsrfWebUiSessionAccessControl(PermissionType.READ);

    private final ModuleLicenseRuntime license;
    private final LicenseAccessRoutes accessRoutes;
    private final LicenseRoutes managementRoutes;
    public LicenseManagementRouteRegistrar(
            ModuleLicenseRuntime license,
            String routePrefix,
            String moduleName,
            String mountAlias
    ) {
        super(routePrefix);
        this.license = license;
        this.accessRoutes = new LicenseAccessRoutes(license);
        this.managementRoutes = new LicenseRoutes(license.service(), moduleName, mountAlias);
    }

    @Override
    public void register(RouteGroup routes) {
        json(routes, WEB_UI_READ, HttpMethod.GET, "/license/access", accessRoutes::access);
        json(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/license/status",
                this::status
        );
        binary(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/license/machine-request",
                managementRoutes::machineRequest
        );
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/license/upload",
                managementRoutes::upload
        );
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/license/revalidate",
                managementRoutes::revalidate
        );
    }

    private Object status(
            com.inductiveautomation.ignition.gateway.dataroutes.RequestContext request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        license.refresh();
        return managementRoutes.status(request, response);
    }
}
