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
            registerGatewayPages();
        } catch (RuntimeException | Error error) {
            logger.error("'{}' module setup failed.", ModuleConstants.MODULE_NAME, error);
            this.context = null;
            rollback(error);
            throw error;
        }
    }

    @Override
    public void startup(LicenseState activationState) {
        try {
            services.startup();
            perspective.startup();
        } catch (RuntimeException | Error error) {
            logger.error("'{}' module startup failed.", ModuleConstants.MODULE_NAME, error);
            context = null;
            rollback(error);
            throw error;
        }
    }

    @Override
    public void shutdown() {
        context = null;
        rollback(null);
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

    private void registerGatewayPages() {
        SystemJsModule webappJsModule = new SystemJsModule(
                ModuleConstants.MODULE_ID,
                "/res/%s/%s".formatted(
                        ModuleConstants.MOUNT_ALIAS,
                        ModuleConstants.WebPackages.WEBAPP_FILENAME
                )
        );
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
                .addPage("Webapp", page -> page
                    .position(10)
                    .mount(
                        "%s/webapp".formatted(ModuleConstants.MOUNT_ALIAS),
                        ModuleConstants.WebPackages.WEBAPP_EXPORT_NAME,
                        webappJsModule
                    )
                )
                .addPage("Counter", page -> page
                    .position(20)
                    .mount(
                        "%s/counter".formatted(ModuleConstants.MOUNT_ALIAS),
                        ModuleConstants.WebPackages.COUNTER_PAGE_EXPORT_NAME,
                        pageJsModule
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

    private void rollback(Throwable cause) {
        Throwable failure = cause;

        try {
            perspective.shutdown();
        } catch (RuntimeException | Error error) {
            failure = merge(failure, error);
        }

        try {
            services.shutdown();
        } catch (RuntimeException | Error error) {
            failure = merge(failure, error);
        }

        if (cause == null) {
            rethrow(failure);
        }
    }

    private static Throwable merge(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
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
