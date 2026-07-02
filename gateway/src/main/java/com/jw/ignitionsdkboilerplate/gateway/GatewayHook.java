package com.jw.ignitionsdkboilerplate.gateway;

import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.inductiveautomation.perspective.common.api.ComponentRegistry;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignitionsdkboilerplate.common.component.ExampleComponent;
import com.jw.ignitionsdkboilerplate.gateway.api.ApiRouteRegistrar;
import com.jw.ignitionsdkboilerplate.gateway.auth.AppPermission;
import com.jw.ignition.auth.AuthConstants;
import com.jw.ignition.auth.api.AuthRouteRegistrar;
import com.jw.ignition.auth.core.AppAuthOptions;
import com.jw.ignition.auth.core.AuthCore;

public class GatewayHook extends AbstractGatewayModuleHook {
    private GatewayContext context;
    private ComponentRegistry componentRegistry;

    private AuthCore auth;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        this.auth = new AuthCore(context, new AppAuthOptions(
                AuthConstants.AUTH_NAMESPACE,
                AppPermission.AUTHORIZATION_MANAGE,
                AppPermission.catalog()
        ));

        // Run auth setup
        auth.setup();

        SystemJsModule jsModule = new SystemJsModule(
            ModuleConstants.MODULE_ID,
            String.format(
                "/res/%s/%s",
                ModuleConstants.MOUNT_ALIAS,
                ModuleConstants.MODULE_WEBAPP_GW_FILENAME
            )
        );

        context.getWebResourceManager().getNavigationModel().getServices()
            .addCategory(ModuleConstants.GATEWAY_NAV_CATEGORY_ID, category -> category
                .label(ModuleConstants.MODULE_NAME)
                .addPage(ModuleConstants.MODULE_WEBAPP_GW_NAV_PAGENAME, page -> page
                    .position(10)
                    .mount(
                        ModuleConstants.MODULE_WEBAPP_GW_NAV_PATH,
                        ModuleConstants.MODULE_WEBAPP_GW_COMPONENT_NAME,
                        jsModule)
                )
            );
    }

    @Override
    public void startup(LicenseState activationState) {
        // Startup auth
        if(auth != null)  {
            auth.startup();
        }

        PerspectiveContext perspective = PerspectiveContext.get(context);
        if (perspective != null) {
            componentRegistry = perspective.getComponentRegistry();
            if (componentRegistry != null) {
                componentRegistry.registerComponent(ExampleComponent.DESCRIPTOR);
            }
        }
    }

    @Override
    public void shutdown() {
        if(auth != null) {
            auth.shutdown();
        }

        if (componentRegistry != null) {
            componentRegistry.removeComponent(ExampleComponent.COMPONENT_ID);
            componentRegistry = null;
        }
        context = null;
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        new AuthRouteRegistrar(context, auth, ModuleConstants.API_PREFIX).register(routes);
        new ApiRouteRegistrar(context, auth, moduleVersion()).register(routes);
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

    private static String moduleVersion() {
        String version = GatewayHook.class
            .getPackage()
            .getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }
}
