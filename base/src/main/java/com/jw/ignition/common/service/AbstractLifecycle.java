package com.jw.ignition.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractLifecycle {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private State state = State.NEW;

    protected final synchronized void runSetup(String type, Runnable process) {
        if (state != State.NEW) {
            throw invalidState(type, "can only be set up once");
        }

        logger.info("Setting up {} '{}'.", type.toLowerCase(), getClass().getName());
        state = State.SETTING_UP;
        try {
            process.run();
            state = State.SET_UP;
            logger.info("{} '{}' set up successfully.", type, getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("{} '{}' setup failed.", type, getClass().getName(), error);
            throw error;
        }
    }

    protected final synchronized void runStartup(String type, Runnable process) {
        if (state == State.RUNNING) {
            return;
        }
        if (state != State.SET_UP) {
            throw invalidState(type, "must be set up before startup");
        }

        logger.info("Starting {} '{}'.", type.toLowerCase(), getClass().getName());
        state = State.STARTING;
        try {
            process.run();
            state = State.RUNNING;
            logger.info("{} '{}' started successfully.", type, getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("{} '{}' startup failed.", type, getClass().getName(), error);
            throw error;
        }
    }

    protected final synchronized void runShutdown(String type, Runnable process) {
        if (state == State.NEW || state == State.STOPPED) {
            return;
        }
        if (state != State.SET_UP && state != State.RUNNING && state != State.FAILED) {
            throw new IllegalStateException("%s '%s' cannot shut down from state %s."
                    .formatted(type, getClass().getName(), state));
        }

        logger.info("Shutting down {} '{}'.", type.toLowerCase(), getClass().getName());
        state = State.STOPPING;
        try {
            process.run();
            state = State.STOPPED;
            logger.info("{} '{}' shut down successfully.", type, getClass().getName());
        } catch (RuntimeException | Error error) {
            state = State.FAILED;
            logger.error("{} '{}' shutdown failed.", type, getClass().getName(), error);
            throw error;
        }
    }

    private IllegalStateException invalidState(String type, String message) {
        return new IllegalStateException("%s '%s' %s; current state: %s."
                .formatted(type, getClass().getName(), message, state));
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
