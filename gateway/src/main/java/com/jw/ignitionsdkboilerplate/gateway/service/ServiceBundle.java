package com.jw.ignitionsdkboilerplate.gateway.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.auth.AuthConstants;
import com.jw.ignition.auth.core.AppAuthOptions;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.service.AbstractBundle;
import com.jw.ignitionsdkboilerplate.gateway.auth.AppPermission;

import java.util.Objects;

public final class ServiceBundle extends AbstractBundle {
    private AuthCore auth;

    @Override
    protected void setupProcess(GatewayContext context) {
        AuthCore authCore = new AuthCore(context, new AppAuthOptions(
                AuthConstants.AUTH_NAMESPACE,
                AppPermission.AUTHORIZATION_MANAGE,
                AppPermission.catalog()
        ));
        authCore.setup();
        auth = authCore;
    }

    @Override
    protected void startupProcess() {
        auth().startup();
    }

    @Override
    protected void shutdownProcess() {
        try {
            if (auth != null) {
                auth.shutdown();
            }
        } finally {
            auth = null;
        }
    }

    public AuthCore auth() {
        return Objects.requireNonNull(auth, "ServiceBundle is not set up");
    }
}
