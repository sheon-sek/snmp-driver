package com.jw.ignition.common.api;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.jw.ignition.common.license.LicenseRequirement;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignition.common.util.Json;

public abstract class AbstractApiRouteRegistrar {
    private final ApiErrorHandler errorHandler = new ApiErrorHandler();
    private final LicenseRequirement defaultLicenseRequirement;
    private final ModuleLicenseRuntime license;
    private final String routePrefix;

    protected AbstractApiRouteRegistrar(String routePrefix) {
        this(routePrefix, null, null);
    }

    protected AbstractApiRouteRegistrar(
            String routePrefix,
            ModuleLicenseRuntime license
    ) {
        this(routePrefix, license, LicenseRequirement.licensed());
    }

    protected AbstractApiRouteRegistrar(
            String routePrefix,
            ModuleLicenseRuntime license,
            LicenseRequirement defaultLicenseRequirement
    ) {
        this.routePrefix = routePrefix;
        this.license = license;
        this.defaultLicenseRequirement = defaultLicenseRequirement;
    }

    public abstract void register(RouteGroup routes);

    protected void json(
            RouteGroup routes,
            HttpMethod method,
            String path,
            RouteHandler handler
    ) {
        json(routes, AccessControlStrategy.OPEN_ROUTE, method, path, null, handler);
    }

    protected void json(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            RouteHandler handler
    ) {
        json(routes, accessControl, method, path, null, handler);
    }

    protected void json(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            LicenseRequirement licenseRequirement,
            RouteHandler handler
    ) {
        mount(
                routes,
                accessControl,
                method,
                path,
                RouteGroup.TYPE_JSON,
                licenseRequirement,
                handler
        );
    }

    protected void binary(
            RouteGroup routes,
            HttpMethod method,
            String path,
            RouteHandler handler
    ) {
        binary(routes, AccessControlStrategy.OPEN_ROUTE, method, path, null, handler);
    }

    protected void binary(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            RouteHandler handler
    ) {
        binary(routes, accessControl, method, path, null, handler);
    }

    protected void binary(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            LicenseRequirement licenseRequirement,
            RouteHandler handler
    ) {
        mount(
                routes,
                accessControl,
                method,
                path,
                RouteGroup.TYPE_OCTET_STREAM,
                licenseRequirement,
                handler
        );
    }

    protected void html(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            RouteHandler handler
    ) {
        mount(
                routes,
                accessControl,
                method,
                path,
                RouteGroup.TYPE_TEXT_HTML,
                null,
                handler
        );
    }

    private void mount(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            String type,
            LicenseRequirement licenseRequirement,
            RouteHandler handler
    ) {
        RouteGroup.RouteMounter mounter = routes.newRoute(routePrefix + path)
                .method(method)
                .accessControl(accessControl)
                .type(type);

        LicenseRequirement effectiveLicenseRequirement = licenseRequirement == null
                ? defaultLicenseRequirement
                : licenseRequirement;
        RouteHandler guarded = handler;
        if (effectiveLicenseRequirement != null) {
            if (license == null) {
                throw new IllegalStateException("Licensed route requires ModuleLicenseRuntime.");
            }
            guarded = license.wrap(effectiveLicenseRequirement, guarded);
        }

        mounter = mounter.handler(errorHandler.wrap(guarded));
        if (RouteGroup.TYPE_JSON.equals(type)) {
            mounter = mounter.renderer(Json.GSON::toJson);
        }
        mounter.mount();
    }
}
