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
    protected final ApiErrorHandler errorHandler;
    protected final AuthFilter authFilter;
    protected final AuthCore auth;
    protected final String routePrefix;

    public AbstractApiRouteRegistrar(GatewayContext context, AuthCore auth, String routePrefix) {
        this.auth  = auth;
        this.routePrefix = routePrefix;

        authFilter = new AuthFilter(context, auth);
        errorHandler = new ApiErrorHandler();
    }

    public abstract void register(RouteGroup routes);

    protected void json(
        RouteGroup routes,
        HttpMethod method,
        String path,
        Boolean requireAuthenticated,
        String permissionKey,
        RouteHandler handler
    ) {
        mount(routes, method, path, RouteGroup.TYPE_JSON, requireAuthenticated, permissionKey, handler);
    }
    protected void json(RouteGroup routes, HttpMethod method, String path, RouteHandler handler) {
        json(routes, method, path, false, "", handler);
    }
    protected void json(RouteGroup routes, AccessControlStrategy accessControl, HttpMethod method, String path, RouteHandler handler) {
        mount(routes, accessControl, method, path, RouteGroup.TYPE_JSON, handler);
    }

    protected void binary(
        RouteGroup routes,
        HttpMethod method,
        String path,
        boolean requireAuthenticated,
        String permissionKey,
        RouteHandler handler
    ) {
        mount(routes, method, path, RouteGroup.TYPE_OCTET_STREAM, requireAuthenticated, permissionKey, handler);
    }
    protected void binary(RouteGroup routes, HttpMethod method, String path, RouteHandler handler) {
        binary(routes, method, path, false, "", handler);
    }
    protected void binary(RouteGroup routes, AccessControlStrategy accessControl, HttpMethod method, String path, RouteHandler handler) {
        mount(routes, accessControl, method, path, RouteGroup.TYPE_OCTET_STREAM, handler);
    }

    protected void mount(
            RouteGroup routes,
            HttpMethod method,
            String path,
            String type,
            boolean requireAuthenticated,
            String permissionKey,
            RouteHandler handler
    ) {
        RouteGroup.RouteMounter mounter = routes.newRoute(routePrefix + path)
                .method(method)
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .type(type);

        if(requireAuthenticated) {
            RouteHandler guarded = permissionKey == null || permissionKey.isBlank()
                    ? authFilter.authenticated(handler)
                    : authFilter.require(permissionKey, handler);
            mounter = mounter.handler(errorHandler.wrap(guarded));
        }
        else {
            mounter = mounter.handler(errorHandler.wrap(handler));
        }

        if(type.equals(RouteGroup.TYPE_JSON)) {
            mounter = mounter.renderer(Json.GSON::toJson);
        }

        mounter.mount();
    }
    protected void mount(
            RouteGroup routes,
            AccessControlStrategy accessControl,
            HttpMethod method,
            String path,
            String type,
            RouteHandler handler
    ) {
        // Mount route
        RouteGroup.RouteMounter mounter =  routes.newRoute(routePrefix + path)
                .method(method)
                .type(type)
                .accessControl(accessControl)
                .handler(errorHandler.wrap(handler));
        if(type.equals(RouteGroup.TYPE_JSON)) {
            mounter = mounter.renderer(Json.GSON::toJson);
        }
        mounter.mount();
    }
}
