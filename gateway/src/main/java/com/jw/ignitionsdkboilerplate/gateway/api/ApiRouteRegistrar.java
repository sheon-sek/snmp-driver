package com.jw.ignitionsdkboilerplate.gateway.api;

import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignitionsdkboilerplate.gateway.auth.AppPermission;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.api.AbstractApiRouteRegistrar;

public final class ApiRouteRegistrar extends AbstractApiRouteRegistrar {
    private final HealthRoutes healthRoutes;

    public ApiRouteRegistrar(GatewayContext context, AuthCore auth, String moduleVersion) {
        super(context, auth, ModuleConstants.API_PREFIX);
        this.healthRoutes = new HealthRoutes(moduleVersion);
    }

    @Override
    public void register(RouteGroup routes) {
        json(routes, HttpMethod.GET, "/health", true, AppPermission.HEALTH_ACCESS, healthRoutes::check);
    }
}
