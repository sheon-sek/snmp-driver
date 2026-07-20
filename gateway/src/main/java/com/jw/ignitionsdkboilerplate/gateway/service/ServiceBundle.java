package com.jw.ignitionsdkboilerplate.gateway.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.auth.AuthConstants;
import com.jw.ignition.auth.core.AppAuthOptions;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.service.AbstractBundle;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignitionsdkboilerplate.gateway.auth.AppPermission;

import java.util.Objects;

public final class ServiceBundle extends AbstractBundle {
    private AuthCore auth;
    private ModuleLicenseRuntime license;

    @Override
    protected void setupProcess(GatewayContext context) {
        ModuleLicenseRuntime licenseRuntime = new ModuleLicenseRuntime(
                context,
                ModuleConstants.MODULE_ID,
                ModuleConstants.MODULE_NAME,
                ModuleConstants.MOUNT_ALIAS
        );
        license = licenseRuntime;
        licenseRuntime.setup();

        AuthCore authCore = new AuthCore(context, new AppAuthOptions(
                AuthConstants.AUTH_NAMESPACE,
                AppPermission.AUTHORIZATION_MANAGE,
                AppPermission.catalog()
        ));
        auth = authCore;
        authCore.setup();
    }

    @Override
    protected void startupProcess() {
        license().startup();
        auth().startup();
    }

    @Override
    protected void shutdownProcess() {
        if (auth != null) {
            auth.shutdown();
            auth = null;
        }
        if (license != null) {
            license.shutdown();
            license = null;
        }
    }

    public AuthCore auth() {
        return Objects.requireNonNull(auth, "ServiceBundle is not set up");
    }

    public ModuleLicenseRuntime license() {
        return Objects.requireNonNull(license, "ServiceBundle is not set up");
    }
}
