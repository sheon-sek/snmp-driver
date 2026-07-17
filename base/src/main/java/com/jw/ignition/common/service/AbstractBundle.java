package com.jw.ignition.common.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;

public abstract class AbstractBundle extends AbstractLifecycle {
    private static final String TYPE = "Bundle";

    protected abstract void setupProcess(GatewayContext context);

    public final void setup(GatewayContext context) {
        runSetup(TYPE, () -> setupProcess(context));
    }

    protected abstract void startupProcess();

    public final void startup() {
        runStartup(TYPE, this::startupProcess);
    }

    protected abstract void shutdownProcess();

    public final void shutdown() {
        runShutdown(TYPE, this::shutdownProcess);
    }
}
