package com.jw.ignition.auth.api;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.auth.core.AppAuthConfig;
import com.jw.ignition.auth.core.AppAuthContext;
import com.jw.ignition.common.api.ApiException;
import com.jw.ignition.common.api.ApiResponses;
import com.jw.ignition.common.util.Json;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

public final class AuthRoutes {
    private final AuthCore auth;

    public AuthRoutes(AuthCore auth) {
        this.auth = auth;
    }

    public Object handleAppAuthMe(RequestContext request, HttpServletResponse response) throws IOException {
        AppAuthContext actor = currentActor(request);
        return ApiResponses.ok(Map.of(
                "source", actor.source().toString(),
                "appAuthenticated", true,
                "username", actor.username(),
                "roles", actor.roles(),
                "securityLevels", actor.securityLevels(),
                "authorities", actor.authorities(),
                "permissions", auth.permissionsFor(actor)
        ));
    }

    public Object handleSecurityLevelRoles(RequestContext request, HttpServletResponse response) {
        return ApiResponses.ok(Map.of(
                "roles", auth.securityLevelRoles()
        ));
    }

    public Object handleUpdateConfig(RequestContext request, HttpServletResponse response) throws IOException {
        AppAuthContext actor = currentActor(request);
        AppAuthConfig incoming;
        try {
            incoming = Json.read(request.getRequest(), AppAuthConfig.class);
        } catch (RuntimeException error) {
            throw new ApiException(
                    HttpServletResponse.SC_BAD_REQUEST,
                    "INVALID_AUTH_CONFIG",
                    "Request body is not valid auth config JSON."
            );
        }
        if (incoming == null) {
            throw new ApiException(
                    HttpServletResponse.SC_BAD_REQUEST,
                    "INVALID_AUTH_CONFIG",
                    "Request body must contain an auth config."
            );
        }

        try {
            AppAuthConfig saved = auth.updateConfig(incoming, actor.username(), incoming.getRevision());
            return ApiResponses.ok(saved);
        } catch (AuthCore.RevisionConflictException error) {
            throw new ApiException(
                    HttpServletResponse.SC_CONFLICT,
                    "REVISION_CONFLICT",
                    error.getMessage(),
                    Map.of(
                            "expectedRevision", error.expectedRevision(),
                            "currentRevision", error.currentRevision()
                    )
            );
        } catch (IllegalArgumentException error) {
            throw new ApiException(
                    HttpServletResponse.SC_BAD_REQUEST,
                    "INVALID_AUTH_CONFIG",
                    error.getMessage()
            );
        }
    }

    public Object handleResetConfig(RequestContext request, HttpServletResponse response) throws IOException {
        AppAuthContext actor = currentActor(request);
        AppAuthConfig saved = auth.resetConfig(actor.username());
        return ApiResponses.ok(saved);
    }

    public Object handleAppAuthConfig(RequestContext request, HttpServletResponse response) throws IOException {
        return ApiResponses.ok(Map.of(
            "config", auth.currentConfig(),
            "permissionCatalog", auth.options().permissions()
        ));
    }

    private AppAuthContext currentActor(RequestContext request) {
        return AuthFilter.getAuthContext(request.getRequest())
                .orElseThrow(() -> new SecurityException("Authenticated user is required."));
    }
}
