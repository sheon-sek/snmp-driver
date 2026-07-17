package com.jw.ignition.common.service;

public abstract class AbstractRuntimeService extends AbstractLifecycle {
    private static final String TYPE = "Service";

    protected abstract void setupProcess();

    public final void setup() {
        runSetup(TYPE, this::setupProcess);
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
