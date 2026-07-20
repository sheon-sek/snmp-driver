package com.jw.ignitionsdkboilerplate.gateway.license;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.jw.ignition.auth.core.AuthCore;
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
    private final String managePermissionKey;

    public LicenseManagementRouteRegistrar(
            GatewayContext context,
            AuthCore auth,
            ModuleLicenseRuntime license,
            String routePrefix,
            String moduleName,
            String mountAlias,
            String managePermissionKey
    ) {
        super(context, auth, routePrefix);
        this.license = license;
        this.accessRoutes = new LicenseAccessRoutes(license);
        this.managementRoutes = new LicenseRoutes(license.service(), moduleName, mountAlias);
        this.managePermissionKey = managePermissionKey;
    }

    @Override
    public void register(RouteGroup routes) {
        json(routes, HttpMethod.GET, "/license/access", true, "", accessRoutes::access);
        json(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/license/status",
                true,
                managePermissionKey,
                this::status
        );
        binary(
                routes,
                WEB_UI_READ,
                HttpMethod.GET,
                "/license/machine-request",
                true,
                managePermissionKey,
                managementRoutes::machineRequest
        );
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/license/upload",
                true,
                managePermissionKey,
                managementRoutes::upload
        );
        json(
                routes,
                WEB_UI_CSRF,
                HttpMethod.POST,
                "/license/revalidate",
                true,
                managePermissionKey,
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
