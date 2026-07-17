package com.jw.ignition.license.api;

import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.jw.ignition.common.api.ApiException;
import com.jw.ignition.license.exception.LicenseAccessException;
import com.jw.ignition.license.exception.LicenseFeatureAccessException;
import com.jw.ignition.license.service.ModuleLicenseService;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

public final class LicenseGateFilter {
    private final ModuleLicenseService license;
    private final String moduleName;

    public LicenseGateFilter(ModuleLicenseService license) {
        this(license, "Module");
    }

    public LicenseGateFilter(ModuleLicenseService license, String moduleName) {
        this.license = license;
        this.moduleName = moduleName == null || moduleName.isBlank() ? "Module" : moduleName.trim();
    }

    public RouteHandler wrap(RouteHandler delegate) {
        return wrap(delegate, null);
    }

    public RouteHandler wrap(RouteHandler delegate, String requiredFeature) {
        return (request, response) -> {
            try {
                if (requiredFeature == null) {
                    license.requireLicensed();
                } else {
                    license.requireFeature(requiredFeature);
                }
            } catch (LicenseFeatureAccessException e) {
                throw new ApiException(
                    HttpServletResponse.SC_FORBIDDEN,
                    "FEATURE_NOT_LICENSED",
                    moduleName + " feature is not licensed.",
                    Map.of("features", e.features())
                );
            } catch (LicenseAccessException e) {
                throw new ApiException(
                    HttpServletResponse.SC_FORBIDDEN,
                    "MODULE_UNLICENSED",
                    moduleName + " is not licensed.",
                    e.status()
                );
            }
            return delegate.handle(request, response);
        };
    }

    public RouteHandler wrapAny(RouteHandler delegate, String... requiredFeatures) {
        return (request, response) -> {
            try {
                license.requireAnyFeature(requiredFeatures);
            } catch (LicenseFeatureAccessException e) {
                throw new ApiException(
                    HttpServletResponse.SC_FORBIDDEN,
                    "FEATURE_NOT_LICENSED",
                    moduleName + " feature is not licensed.",
                    Map.of("features", e.features())
                );
            } catch (LicenseAccessException e) {
                throw new ApiException(
                    HttpServletResponse.SC_FORBIDDEN,
                    "MODULE_UNLICENSED",
                    moduleName + " is not licensed.",
                    e.status()
                );
            }
            return delegate.handle(request, response);
        };
    }
}
