package com.jw.snmpdriver.gateway.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.common.service.AbstractBundle;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.snmpdriver.common.ModuleConstants;

import java.util.Objects;

public final class ServiceBundle extends AbstractBundle {
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
    }

    @Override
    protected void startupProcess() {
        license().startup();
    }

    @Override
    protected void shutdownProcess() {
        if (license != null) {
            license.shutdown();
            license = null;
        }
    }

    public ModuleLicenseRuntime license() {
        return Objects.requireNonNull(license, "ServiceBundle is not set up");
    }
}
