package com.jw.ignition.common.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractRuntimeServiceTest {

    @Test
    void normalLifecycleIsIdempotent() {
        TestService service = new TestService();

        service.setup();
        service.startup();
        service.startup();
        service.shutdown();
        service.shutdown();

        assertEquals(1, service.setupCount);
        assertEquals(1, service.startupCount);
        assertEquals(1, service.shutdownCount);
    }

    @Test
    void setupFailureCanBeCleanedUp() {
        TestService service = new TestService();
        service.failSetup = true;

        assertThrows(IllegalStateException.class, service::setup);
        service.shutdown();

        assertEquals(1, service.shutdownCount);
    }

    @Test
    void startupFailureCanBeCleanedUp() {
        TestService service = new TestService();
        service.setup();
        service.failStartup = true;

        assertThrows(IllegalStateException.class, service::startup);
        service.shutdown();

        assertEquals(1, service.shutdownCount);
    }

    @Test
    void shutdownFailureCanBeRetried() {
        TestService service = new TestService();
        service.setup();
        service.startup();
        service.failShutdownOnce = true;

        assertThrows(IllegalStateException.class, service::shutdown);
        service.shutdown();

        assertEquals(2, service.shutdownCount);
    }

    private static final class TestService extends AbstractRuntimeService {
        private int setupCount;
        private int startupCount;
        private int shutdownCount;
        private boolean failSetup;
        private boolean failStartup;
        private boolean failShutdownOnce;

        @Override
        protected void setupProcess() {
            setupCount += 1;
            if (failSetup) {
                throw new IllegalStateException("setup failed");
            }
        }

        @Override
        protected void startupProcess() {
            startupCount += 1;
            if (failStartup) {
                throw new IllegalStateException("startup failed");
            }
        }

        @Override
        protected void shutdownProcess() {
            shutdownCount += 1;
            if (failShutdownOnce) {
                failShutdownOnce = false;
                throw new IllegalStateException("shutdown failed");
            }
        }
    }
}
