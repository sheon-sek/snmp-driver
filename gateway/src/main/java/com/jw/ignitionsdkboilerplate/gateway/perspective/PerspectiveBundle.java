package com.jw.ignitionsdkboilerplate.gateway.perspective;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.perspective.common.api.ComponentRegistry;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import com.jw.ignition.common.service.AbstractBundle;
import com.jw.ignitionsdkboilerplate.common.component.ExampleWebApp;

import java.util.Objects;

public final class PerspectiveBundle extends AbstractBundle {
    private GatewayContext context;
    private ComponentRegistry componentRegistry;
    private boolean componentsRegistered;

    @Override
    protected void setupProcess(GatewayContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    protected void startupProcess() {
        PerspectiveContext perspectiveContext = Objects.requireNonNull(
                PerspectiveContext.get(context),
                "Perspective context is unavailable"
        );
        componentRegistry = Objects.requireNonNull(
                perspectiveContext.getComponentRegistry(),
                "Perspective component registry is unavailable"
        );
        registerComponents();
        componentsRegistered = true;
    }

    @Override
    protected void shutdownProcess() {
        try {
            if (componentsRegistered && componentRegistry != null) {
                unregisterComponents();
            }
        } finally {
            componentsRegistered = false;
            componentRegistry = null;
            context = null;
        }
    }

    private void registerComponents() {
        logger.info("Registering perspective components...");
        componentRegistry.registerComponent(ExampleWebApp.DESCRIPTOR);
    }

    private void unregisterComponents() {
        logger.info("Removing registered perspective components...");
        componentRegistry.removeComponent(ExampleWebApp.COMPONENT_ID);
    }
}
