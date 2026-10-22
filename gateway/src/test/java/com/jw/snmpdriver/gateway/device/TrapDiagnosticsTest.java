package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.snmp.trap.TrapListenerHandle;
import com.jw.snmpdriver.snmp.trap.TrapListenerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TrapDiagnosticsTest {
    private final Map<String, String> nodes = new LinkedHashMap<>();
    private final TrapDiagnostics diagnostics = new TrapDiagnostics(nodes::put);
    private final ManualScheduler scheduler = new ManualScheduler();
    private int failures;

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private TrapListenerRegistry registry() {
        return new TrapListenerRegistry((address, port) -> {
            if (failures > 0) {
                failures--;
                throw new IOException("Address already in use");
            }
            return () -> { };
        }, scheduler);
    }

    @Test
    void disabledShowsStatusDisabledWithEmptyDetails() {
        diagnostics.showDisabled();

        assertEquals("Disabled", nodes.get("Status"));
        assertEquals("", nodes.get("LastError"));
        assertEquals("", nodes.get("ListenerEndpoint"));
    }

    @Test
    void boundListenerShowsListeningAndEndpoint() {
        TrapListenerHandle handle = registry().acquire("dev-a", "0.0.0.0", 10162, diagnostics::refresh);

        diagnostics.track(handle);

        assertEquals("Listening", nodes.get("Status"));
        assertEquals("", nodes.get("LastError"));
        assertEquals("0.0.0.0:10162", nodes.get("ListenerEndpoint"));
    }

    @Test
    void bindFailureShowsDegradedWithReasonAndRemediationThenRecovers() {
        failures = 1;
        TrapListenerHandle handle = registry().acquire("dev-a", "0.0.0.0", 162, diagnostics::refresh);

        diagnostics.track(handle);

        assertEquals("Degraded", nodes.get("Status"));
        String error = nodes.get("LastError");
        assertTrue(error.contains("Address already in use"), error);
        assertTrue(error.contains("10162") && error.contains("nftables"), error);
        assertTrue(error.contains("SNMPTRAP"), error);
        assertEquals("0.0.0.0:162", nodes.get("ListenerEndpoint"));

        scheduler.runPending();

        assertEquals("Listening", nodes.get("Status"));
        assertEquals("", nodes.get("LastError"));
    }

    @Test
    void rejectedHandleShowsDegradedWithConflictReason() {
        TrapListenerRegistry registry = registry();
        registry.acquire("dev-a", "0.0.0.0", 10162);
        TrapListenerHandle rejected = registry.acquire("dev-b", "127.0.0.1", 10162, diagnostics::refresh);

        diagnostics.track(rejected);

        assertEquals("Degraded", nodes.get("Status"));
        assertTrue(nodes.get("LastError").contains("dev-a"), nodes.get("LastError"));
        assertEquals("", nodes.get("ListenerEndpoint"));
    }

    @Test
    void conflictNamesOwnerAndAddressWhileFirstArrivalKeepsListeningAndRecoversAfterRelease() {
        TrapListenerRegistry registry = registry();
        Map<String, String> firstNodes = new LinkedHashMap<>();
        TrapDiagnostics firstDiagnostics = new TrapDiagnostics(firstNodes::put);
        TrapListenerHandle first = registry.acquire("dev-a", "0.0.0.0", 10162, firstDiagnostics::refresh);
        firstDiagnostics.track(first);

        diagnostics.track(registry.acquire("dev-b", "127.0.0.1", 10162, diagnostics::refresh));

        assertEquals("Degraded", nodes.get("Status"));
        String error = nodes.get("LastError");
        assertTrue(error.contains("dev-a") && error.contains("0.0.0.0"), error);
        assertTrue(error.contains("127.0.0.1"), error);
        assertEquals("Listening", firstNodes.get("Status"));
        assertEquals("", firstNodes.get("LastError"));

        first.release();
        firstDiagnostics.released();
        diagnostics.track(registry.acquire("dev-b", "127.0.0.1", 10162, diagnostics::refresh));

        assertEquals("Listening", nodes.get("Status"));
        assertEquals("", nodes.get("LastError"));
        assertEquals("127.0.0.1:10162", nodes.get("ListenerEndpoint"));
        assertEquals("Disabled", firstNodes.get("Status"));
    }

    @Test
    void conflictAfterFirstArrivalReleasesPointsAtRemainingHolder() {
        TrapListenerRegistry registry = registry();
        TrapListenerHandle a = registry.acquire("dev-a", "0.0.0.0", 10162);
        TrapListenerHandle b = registry.acquire("dev-b", "0.0.0.0", 10162);
        a.release();

        diagnostics.track(registry.acquire("dev-c", "127.0.0.1", 10162, diagnostics::refresh));

        String error = nodes.get("LastError");
        assertTrue(error.contains("dev-b") && !error.contains("dev-a"), error);
        b.release();
    }

    @Test
    void reconfiguredDeviceListensAgainOnSamePort() {
        TrapListenerRegistry registry = registry();
        TrapListenerHandle old = registry.acquire("dev-a", "0.0.0.0", 10162, diagnostics::refresh);
        diagnostics.track(old);
        old.release();
        diagnostics.released();
        assertEquals("Disabled", nodes.get("Status"));

        diagnostics.track(registry.acquire("dev-a", "0.0.0.0", 10162, diagnostics::refresh));

        assertEquals("Listening", nodes.get("Status"));
        assertEquals("0.0.0.0:10162", nodes.get("ListenerEndpoint"));
    }

    @Test
    void nodeDescriptionsStateM1aOnlyHoldsThePort() {
        for (String name : List.of("Status", "LastError", "ListenerEndpoint")) {
            String description = TrapDiagnostics.describe(name);
            assertTrue(description.contains("M1a 仅占用端口，尚未处理收到的 Notification"), description);
            assertTrue(description.contains(name), description);
        }
    }

    @Test
    void unavailableRegistryShowsDegraded() {
        diagnostics.showUnavailable("Trap 监听器注册表不可用");

        assertEquals("Degraded", nodes.get("Status"));
        assertTrue(nodes.get("LastError").contains("注册表不可用"));
    }

    @Test
    void releaseAndShutdownReturnToDisabledAndDropWatcher() {
        TrapListenerHandle handle = registry().acquire("dev-a", "0.0.0.0", 10162, diagnostics::refresh);
        diagnostics.track(handle);

        diagnostics.released();

        assertEquals("Disabled", nodes.get("Status"));
        assertEquals("", nodes.get("ListenerEndpoint"));
        diagnostics.refresh();
        assertEquals("Disabled", nodes.get("Status"), "release 之后刷新不得复活节点");
    }

    @Test
    void onlyTrapNodesAreWritten() {
        failures = 1;
        diagnostics.track(registry().acquire("dev-a", "0.0.0.0", 162, diagnostics::refresh));
        scheduler.runPending();

        assertEquals(List.of("Status", "LastError", "ListenerEndpoint"),
                new ArrayList<>(nodes.keySet()));
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (isShutdown()) {
                throw new RejectedExecutionException("shut down");
            }
            tasks.add(command);
            return null;
        }

        void runPending() {
            List<Runnable> ready;
            synchronized (this) {
                ready = new ArrayList<>(tasks);
                tasks.clear();
            }
            ready.forEach(Runnable::run);
        }
    }
}
