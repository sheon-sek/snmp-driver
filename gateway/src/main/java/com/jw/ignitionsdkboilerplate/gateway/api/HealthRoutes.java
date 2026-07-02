package com.jw.ignitionsdkboilerplate.gateway.api;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignition.common.api.ApiResponses;
import jakarta.servlet.http.HttpServletResponse;

public final class HealthRoutes {
    private final String version;

    public HealthRoutes(String version) {
        this.version = version;
    }

    public Object check(RequestContext request, HttpServletResponse response) {
        return ApiResponses.ok(payload());
    }

    HealthPayload payload() {
        return new HealthPayload("ok", ModuleConstants.MODULE_NAME, version);
    }

    public record HealthPayload(String status, String module, String version) {
    }
}
