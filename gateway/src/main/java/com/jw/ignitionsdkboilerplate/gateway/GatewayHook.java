package com.jw.ignitionsdkboilerplate.gateway;

import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignitionsdkboilerplate.gateway.api.ApiRouteRegistrar;
import com.jw.ignition.auth.api.AuthRouteRegistrar;
import com.jw.ignitionsdkboilerplate.gateway.perspective.PerspectiveBundle;
import com.jw.ignitionsdkboilerplate.gateway.service.ServiceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayHook extends AbstractGatewayModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHook.class);

    private GatewayContext context;
    private final ServiceBundle services = new ServiceBundle();
    private final PerspectiveBundle perspective = new PerspectiveBundle();

    @Override
    public void setup(GatewayContext context) {
        this.context = context;

        try {
            services.setup(context);
            perspective.setup(context);

            registerGatewayPage();
        } catch (Exception error) {
            logger.error("'%s' module setup failed.".formatted(ModuleConstants.MODULE_NAME));
        }
    }

    @Override
    public void startup(LicenseState activationState) {
        services.startup();
        perspective.startup();
    }

    @Override
    public void shutdown() {
        Throwable failure = null;
        try {
            perspective.shutdown();
        } catch (RuntimeException | Error error) {
            failure = error;
        }

        try {
            services.shutdown();
        } catch (RuntimeException | Error error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }

        context = null;
        rethrow(failure);
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        new AuthRouteRegistrar(context, services.auth(), ModuleConstants.API_PREFIX).register(routes);
        new ApiRouteRegistrar(context, services.auth(), moduleVersion()).register(routes);
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

    // --------------------------------------------------------- //

    private void registerGatewayPage() {
        SystemJsModule webappJsModule = new SystemJsModule(
                ModuleConstants.MODULE_ID,
                "/res/%s/webapp.js".formatted(ModuleConstants.MOUNT_ALIAS)
        );

        context.getWebResourceManager().getNavigationModel().getServices()
            .addCategory(ModuleConstants.GATEWAY_NAV_CATEGORY_ID, category -> category
                .label(ModuleConstants.MODULE_NAME)
                .addPage("Webapp", page -> page
                    .position(10)
                    .mount(
                        "%s/webapp".formatted(ModuleConstants.MOUNT_ALIAS),
                        "IgnitionSdkBoilerplateWebApp",
                        webappJsModule
                    )
                )
            );
    }

    // --------------------------------------------------------- //

    private static String moduleVersion() {
        String version = GatewayHook.class
            .getPackage()
            .getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
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
