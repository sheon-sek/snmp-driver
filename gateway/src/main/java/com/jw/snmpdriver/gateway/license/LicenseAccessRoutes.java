package com.jw.snmpdriver.gateway.license;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.jw.ignition.common.api.ApiResponses;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import jakarta.servlet.http.HttpServletResponse;

public final class LicenseAccessRoutes {
    private final ModuleLicenseRuntime license;

    public LicenseAccessRoutes(ModuleLicenseRuntime license) {
        this.license = license;
    }

    public Object access(RequestContext request, HttpServletResponse response) {
        return ApiResponses.ok(license.access());
    }
}
