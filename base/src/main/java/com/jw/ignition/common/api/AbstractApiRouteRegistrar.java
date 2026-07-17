package com.jw.ignition.common.api;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.auth.api.AuthFilter;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.util.Json;

public abstract class AbstractApiRouteRegistrar {
    private final ApiErrorHandler errorHandler;
    private final AuthFilter authFilter;
    protected final AuthCore auth;
    private final String routePrefix;

    protected AbstractApiRouteRegistrar(GatewayContext context, AuthCore auth, String routePrefix) {
        this.auth = auth;
        this.routePrefix = routePrefix;

        authFilter = new AuthFilter(context, auth);
        errorHandler = new ApiErrorHandler();
    }

    public abstract void register(RouteGroup routes);

    protected void json(
        RouteGroup routes,
        HttpMethod method,
        String path,
        boolean requireAuthenticated,
        String permissionKey,
        RouteHandler handler
    ) {
        json(
                routes,
                AccessControlStrategy.OPEN_ROUTE,
                method,
                path,
                requireAuthenticated,
                permissionKey,
                handler
        );
    }

    protected void json(RouteGroup routes, HttpMethod method, String path, RouteHandler handler) {
        json(routes, method, path, false, "", handler);
    }

    protected void json(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            boolean requireAuthenticated,
            String permissionKey,
            RouteHandler handler
    ) {
        mount(
                routes,
                accessControl,
                method,
                path,
                RouteGroup.TYPE_JSON,
                requireAuthenticated,
                permissionKey,
                handler
        );
    }

    protected void json(RouteGroup routes, AccessControlStrategy accessControl, HttpMethod method, String path, RouteHandler handler) {
        json(routes, accessControl, method, path, false, "", handler);
    }

    protected void binary(
        RouteGroup routes,
        HttpMethod method,
        String path,
        boolean requireAuthenticated,
        String permissionKey,
        RouteHandler handler
    ) {
        binary(
                routes,
                AccessControlStrategy.OPEN_ROUTE,
                method,
                path,
                requireAuthenticated,
                permissionKey,
                handler
        );
    }

    protected void binary(RouteGroup routes, HttpMethod method, String path, RouteHandler handler) {
        binary(routes, method, path, false, "", handler);
    }

    protected void binary(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            boolean requireAuthenticated,
            String permissionKey,
            RouteHandler handler
    ) {
        mount(
                routes,
                accessControl,
                method,
                path,
                RouteGroup.TYPE_OCTET_STREAM,
                requireAuthenticated,
                permissionKey,
                handler
        );
    }

    protected void binary(RouteGroup routes, AccessControlStrategy accessControl, HttpMethod method, String path, RouteHandler handler) {
        binary(routes, accessControl, method, path, false, "", handler);
    }

    protected void mount(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            String type,
            boolean requireAuthenticated,
            String permissionKey,
            RouteHandler handler
    ) {
        RouteGroup.RouteMounter mounter = routes.newRoute(routePrefix + path)
                .method(method)
                .accessControl(accessControl)
                .type(type);

        RouteHandler guarded = handler;
        if (requireAuthenticated) {
            guarded = permissionKey == null || permissionKey.isBlank()
                    ? authFilter.authenticated(handler)
                    : authFilter.require(permissionKey, handler);
        }
        mounter = mounter.handler(errorHandler.wrap(guarded));

        if (RouteGroup.TYPE_JSON.equals(type)) {
            mounter = mounter.renderer(Json.GSON::toJson);
        }

        mounter.mount();
    }
}
