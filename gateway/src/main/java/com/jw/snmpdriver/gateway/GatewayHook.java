package com.jw.snmpdriver.gateway;

import java.util.List;
import java.util.Optional;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.jw.snmpdriver.common.ModuleConstants;
import com.jw.snmpdriver.gateway.device.SNMPDevice;
import com.jw.snmpdriver.gateway.device.SNMPDeviceExtensionPoint;
import com.jw.snmpdriver.gateway.license.LicenseManagementRouteRegistrar;
import com.jw.snmpdriver.gateway.service.ServiceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GatewayHook extends AbstractDeviceModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHook.class);

    private final ServiceBundle services = new ServiceBundle();
    private final SNMPDeviceExtensionPoint extensionPoint = new SNMPDeviceExtensionPoint(services);

    private GatewayContext context;
    private boolean bundleRegistered;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;

        try {
            super.setup(context);
            BundleUtil.get().addBundle(SNMPDevice.class);
            bundleRegistered = true;
            services.setup(context);
            registerGatewayPages();
        } catch (RuntimeException | Error error) {
            logger.error("'{}' module setup failed.", ModuleConstants.MODULE_NAME, error);
            cleanupServicesAndBundle(error);
            this.context = null;
            throw error;
        }
    }

    @Override
    public void startup(LicenseState activationState) {
        try {
            super.startup(activationState);
            services.startup();
        } catch (RuntimeException | Error error) {
            logger.error("'{}' module startup failed.", ModuleConstants.MODULE_NAME, error);
            cleanupServicesAndBundle(error);
            context = null;
            throw error;
        }
    }

    @Override
    public void shutdown() {
        Throwable failure = null;

        try {
            services.shutdown();
        } catch (RuntimeException | Error error) {
            failure = error;
            logger.error("SNMP Driver service shutdown failed", error);
        }

        failure = removeBundle(failure);

        try {
            super.shutdown();
        } catch (RuntimeException | Error error) {
            failure = merge(failure, error);
            logger.error("SNMP Driver module shutdown failed", error);
        }

        context = null;
        rethrow(failure);
    }

    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        return List.of(extensionPoint);
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        new LicenseManagementRouteRegistrar(
                services.license(),
                ModuleConstants.API_PREFIX,
                ModuleConstants.MODULE_NAME,
                ModuleConstants.MOUNT_ALIAS
        ).register(routes);
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of(ModuleConstants.MOUNT_ALIAS);
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    private void registerGatewayPages() {
        SystemJsModule pageJsModule = new SystemJsModule(
                "%s.page".formatted(ModuleConstants.MODULE_ID),
                "/res/%s/%s".formatted(
                        ModuleConstants.MOUNT_ALIAS,
                        ModuleConstants.WebPackages.PAGE_FILENAME
                )
        );

        context.getWebResourceManager().getNavigationModel().getServices()
            .addCategory(ModuleConstants.GATEWAY_NAV_CATEGORY_ID, category -> category
                .label(ModuleConstants.MODULE_NAME)
                .addPage("License", page -> page
                    .position(10)
                    .mount(
                        "%s/license".formatted(ModuleConstants.MOUNT_ALIAS),
                        ModuleConstants.WebPackages.LICENSE_PAGE_EXPORT_NAME,
                        pageJsModule
                    )
                )
            );
    }

    private void cleanupServicesAndBundle(Throwable cause) {
        try {
            services.shutdown();
        } catch (RuntimeException | Error cleanupError) {
            cause.addSuppressed(cleanupError);
            logger.warn("SNMP Driver service cleanup failed", cleanupError);
        }
        removeBundle(cause);
    }

    private Throwable removeBundle(Throwable failure) {
        if (!bundleRegistered) {
            return failure;
        }

        try {
            BundleUtil.get().removeBundle(SNMPDevice.class);
            bundleRegistered = false;
        } catch (RuntimeException | Error error) {
            failure = merge(failure, error);
            logger.warn("SNMP Driver bundle cleanup failed", error);
        }
        return failure;
    }

    private static Throwable merge(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
