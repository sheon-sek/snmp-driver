package com.jw.ignition.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractRuntimeService {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private State state = State.NEW;

    protected abstract void setupProcess();

    public final synchronized void setup() {
        if (state != State.NEW) {
            throw new IllegalStateException("Service '%s' can only be set up once; current state: %s."
                    .formatted(getClass().getName(), state));
        }

        logger.info("Setting up service '{}'.", getClass().getName());
        state = State.SETTING_UP;
        try {
            setupProcess();
            state = State.SET_UP;
            logger.info("Service '{}' set up successfully.", getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("Service '{}' setup failed.", getClass().getName(), error);
            throw error;
        }
    }

    protected abstract void startupProcess();

    public final synchronized void startup() {
        if (state == State.RUNNING) {
            return;
        }
        if (state != State.SET_UP) {
            throw new IllegalStateException("Service '%s' must be set up before startup; current state: %s."
                    .formatted(getClass().getName(), state));
        }

        logger.info("Starting service '{}'.", getClass().getName());
        state = State.STARTING;
        try {
            startupProcess();
            state = State.RUNNING;
            logger.info("Service '{}' started successfully.", getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("Service '{}' startup failed.", getClass().getName(), error);
            throw error;
        }
    }

    protected abstract void shutdownProcess();

    public final synchronized void shutdown() {
        if (state == State.NEW || state == State.STOPPED) {
            return;
        }
        if (state != State.SET_UP && state != State.RUNNING && state != State.FAILED) {
            throw new IllegalStateException("Service '%s' cannot shut down from state %s."
                    .formatted(getClass().getName(), state));
        }

        logger.info("Shutting down service '{}'.", getClass().getName());
        state = State.STOPPING;
        try {
            shutdownProcess();
            state = State.STOPPED;
            logger.info("Service '{}' shut down successfully.", getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("Service '{}' shutdown failed.", getClass().getName(), error);
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
