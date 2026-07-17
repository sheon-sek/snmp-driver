package com.jw.ignition.common.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractBundle {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private State state = State.NEW;

    protected abstract void setupProcess(GatewayContext context);

    public final synchronized void setup(GatewayContext context) {
        if (state != State.NEW) {
            throw new IllegalStateException("Bundle '%s' can only be set up once; current state: %s."
                    .formatted(getClass().getName(), state));
        }

        logger.info("Setting up bundle '{}'.", getClass().getName());
        state = State.SETTING_UP;
        try {
            setupProcess(context);
            state = State.SET_UP;
            logger.info("Bundle '{}' set up successfully.", getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("Bundle '{}' setup failed.", getClass().getName(), error);
            throw error;
        }
    }

    protected abstract void startupProcess();

    public final synchronized void startup() {
        if (state == State.RUNNING) {
            return;
        }
        if (state != State.SET_UP) {
            throw new IllegalStateException("Bundle '%s' must be set up before startup; current state: %s."
                    .formatted(getClass().getName(), state));
        }

        logger.info("Starting bundle '{}'.", getClass().getName());
        state = State.STARTING;
        try {
            startupProcess();
            state = State.RUNNING;
            logger.info("Bundle '{}' started successfully.", getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("Bundle '{}' startup failed.", getClass().getName(), error);
            throw error;
        }
    }

    protected abstract void shutdownProcess();

    public final synchronized void shutdown() {
        if (state == State.NEW || state == State.STOPPED) {
            return;
        }
        if (state != State.SET_UP && state != State.RUNNING && state != State.FAILED) {
            throw new IllegalStateException("Bundle '%s' cannot shut down from state %s."
                    .formatted(getClass().getName(), state));
        }

        logger.info("Shutting down bundle '{}'.", getClass().getName());
        state = State.STOPPING;
        try {
            shutdownProcess();
            state = State.STOPPED;
            logger.info("Bundle '{}' shut down successfully.", getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("Bundle '{}' shutdown failed.", getClass().getName(), error);
            throw error;
        }
    }

    private enum State {
        NEW,
        SETTING_UP,
        SET_UP,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED,
        STOPPED
    }
}
