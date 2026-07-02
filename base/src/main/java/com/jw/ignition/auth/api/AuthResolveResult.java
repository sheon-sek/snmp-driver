package com.jw.ignition.auth.api;

import com.jw.ignition.auth.core.AppAuthContext;

public record AuthResolveResult(
        boolean authenticated,
        AppAuthContext authContext,
        String message
) {
    public static AuthResolveResult authenticated(AppAuthContext authContext) {
        return new AuthResolveResult(true, authContext, "");
    }

    public static AuthResolveResult unauthenticated(String message) {
        return new AuthResolveResult(false, null, message);
    }
}
