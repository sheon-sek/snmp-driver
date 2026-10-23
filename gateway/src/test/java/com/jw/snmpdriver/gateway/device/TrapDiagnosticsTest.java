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

import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import com.jw.snmpdriver.snmp.trap.TrapListenerHandle;
import com.jw.snmpdriver.snmp.trap.TrapNotification;
import com.jw.snmpdriver.snmp.trap.TrapListenerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TrapDiagnosticsTest {
    private final Map<String, Object> nodes = new LinkedHashMap<>();
    private final TrapDiagnostics diagnostics = new TrapDiagnostics(nodes::put);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<TrapListenerRegistry> registries = new ArrayList<>();
    private int failures;

    private String text(String name) {
        return (String) nodes.get(name);
    }

    @AfterEach
    void tearDown() {
        registries.forEach(TrapListenerRegistry::closeAll);
        scheduler.shutdownNow();
    }

    private TrapListenerRegistry registry() {
        TrapListenerRegistry registry = new TrapListenerRegistry((address, port) -> {
            if (failures > 0) {
                failures--;
                throw new IOException("Address already in use");
            }
            return () -> { };
        }, scheduler);
        registries.add(registry);
        return registry;
    }

    @Test
    void disabledShowsStatusDisabledWithEmptyDetails() {
        diagnostics.showDisabled();

        assertEquals("Disabled", nodes.get("Status"));
        assertEquals("", nodes.get("LastError"));
        assertEquals("", nodes.get("ListenerEndpoint"));
    }

    @Test
    void noticeShowsInLastErrorOnlyWhileListeningAndIsClearedByRefresh() {
        diagnostics.notice("ignored before tracking");
        assertEquals(null, nodes.get("LastError"));

        diagnostics.track(registry().acquire("dev-a", "0.0.0.0", 10162, diagnostics::refresh));
        diagnostics.notice("community 不匹配");
        assertEquals("community 不匹配", nodes.get("LastError"));

        nodes.put("LastError", "kept");
        diagnostics.notice("community 不匹配");
        assertEquals("kept", nodes.get("LastError"), "an unchanged notice must not rewrite the node");

        diagnostics.refresh();
        assertEquals("", nodes.get("LastError"));
    }

    @Test
    void unlicensedShowsStatusUnlicensedAndFollowsLicenseOnRefresh() {
        java.util.concurrent.atomic.AtomicReference<LicenseGate.Verdict> verdict =
                new java.util.concurrent.atomic.AtomicReference<>(LicenseGate.Verdict.NOT_AVAILABLE);
        TrapDiagnostics licensed = new TrapDiagnostics(nodes::put, verdict::get);
        licensed.track(registry().acquire("dev-a", "0.0.0.0", 10162, licensed::refresh));

        assertEquals("Unlicensed", nodes.get("Status"));
        assertTrue(text("LastError").contains("许可证"));
        assertEquals("0.0.0.0:10162", nodes.get("ListenerEndpoint"));

        verdict.set(LicenseGate.Verdict.EXPIRED);
        licensed.refresh();
        assertTrue(text("LastError").contains("过期"));

        verdict.set(LicenseGate.Verdict.LICENSED);
        licensed.refresh();
        assertEquals("Listening", nodes.get("Status"));
        assertEquals("", nodes.get("LastError"));
    }

    @Test
    void degradedOutranksUnlicensed() {
        TrapDiagnostics licensed = new TrapDiagnostics(nodes::put, () -> LicenseGate.Verdict.NOT_AVAILABLE);
        failures = 1;
        licensed.track(registry().acquire("dev-a", "0.0.0.0", 10162, licensed::refresh));

        assertEquals("Degraded", nodes.get("Status"));
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
        String error = text("LastError");
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
        assertTrue(text("LastError").contains("dev-a"), text("LastError"));
        assertTrue(text("LastError").contains("重新保存或重新启用"), text("LastError"));
        assertTrue(!text("LastError").contains("每 30 秒自动重试"), text("LastError"));
        assertEquals("", nodes.get("ListenerEndpoint"));
    }

    @Test
    void conflictNamesOwnerAndAddressWhileFirstArrivalKeepsListeningAndRecoversAfterRelease() {
        TrapListenerRegistry registry = registry();
        Map<String, Object> firstNodes = new LinkedHashMap<>();
        TrapDiagnostics firstDiagnostics = new TrapDiagnostics(firstNodes::put);
        TrapListenerHandle first = registry.acquire("dev-a", "0.0.0.0", 10162, firstDiagnostics::refresh);
        firstDiagnostics.track(first);

        diagnostics.track(registry.acquire("dev-b", "127.0.0.1", 10162, diagnostics::refresh));

        assertEquals("Degraded", nodes.get("Status"));
        String error = text("LastError");
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

        String error = text("LastError");
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
    void nodeDescriptionsNoLongerClaimNotificationsAreIgnored() {
        for (String name : List.of("Status", "LastError", "ListenerEndpoint")) {
            String description = TrapDiagnostics.describe(name);
            assertTrue(!description.contains("M1a"), description);
            assertTrue(description.contains("Last/Json"), description);
            assertTrue(description.contains(name), description);
        }
    }

    @Test
    void unavailableRegistryShowsDegraded() {
        diagnostics.showUnavailable("Trap 监听器注册表不可用");

        assertEquals("Degraded", nodes.get("Status"));
        assertTrue(text("LastError").contains("注册表不可用"));
    }

    @Test
    void releaseAndShutdownReturnToDisabledAndDropWatcher() {
        TrapListenerHandle handle = registry().acquire("dev-a", "0.0.0.0", 10162, diagnostics::refresh);
        diagnostics.track(handle);

        handle.release();
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

    private static TrapNotification notification(String trapOid) throws Exception {
        return new TrapNotification(java.time.Instant.parse("2026-01-02T03:04:05Z"),
                TrapNotification.Version.V2C, TrapNotification.PduType.INFORM,
                java.net.InetAddress.getByName("192.0.2.7"), 40162, null, 99L, trapOid,
                List.of(new org.snmp4j.smi.VariableBinding(org.snmp4j.mp.SnmpConstants.sysUpTime,
                        new org.snmp4j.smi.TimeTicks(99))), false);
    }

    @Test
    void acceptedNotificationPublishesEveryLastNodeFromOneSnapshot() throws Exception {
        diagnostics.accept(notification("1.3.6.1.6.3.1.1.5.4"));
        diagnostics.accept(notification("1.3.6.1.6.3.1.1.5.3"));

        assertEquals(2L, nodes.get("Last/Sequence"));
        assertEquals(java.time.Instant.parse("2026-01-02T03:04:05Z"), nodes.get("Last/ReceivedTime"));
        assertEquals("INFORM", nodes.get("Last/PduType"));
        assertEquals("V2C", nodes.get("Last/Version"));
        assertEquals("1.3.6.1.6.3.1.1.5.3", nodes.get("Last/TrapOid"));
        assertEquals("192.0.2.7:40162", nodes.get("Last/Source"));
        assertEquals("", nodes.get("Last/AgentAddress"));
        assertEquals(99L, nodes.get("Last/AgentUpTime"));
        assertTrue(text("Last/Varbinds").startsWith("[{\"oid\":\"1.3.6.1.2.1.1.3.0\""));
        assertTrue(text("Last/Json").startsWith("{\"sequence\":2,"), text("Last/Json"));
        assertTrue(text("Last/Json").contains("\"varbinds\":" + text("Last/Varbinds")));
    }

    @Test
    void counterNodesAreWrittenOnlyWhenTheirValueChanged() {
        java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
        Map<String, java.util.function.LongSupplier> sources = new LinkedHashMap<>();
        sources.put("ReceivedCount", received::get);
        sources.put("AcceptedCount", () -> 0L);
        List<String> writes = new ArrayList<>();
        TrapDiagnostics counting = new TrapDiagnostics((name, value) -> writes.add(name + "=" + value),
                () -> LicenseGate.Verdict.LICENSED, sources);

        counting.flushCounters();
        assertEquals(List.of(), writes, "unchanged counters must not be written");

        received.set(5);
        counting.flushCounters();
        received.set(7);
        received.set(9);
        counting.flushCounters();
        counting.flushCounters();

        assertEquals(List.of("ReceivedCount=5", "ReceivedCount=9"), writes);
    }

    @Test
    void lastNodesGoBadWithoutLicenseAndComeBackWithTheirValues() throws Exception {
        java.util.concurrent.atomic.AtomicReference<LicenseGate.Verdict> verdict =
                new java.util.concurrent.atomic.AtomicReference<>(LicenseGate.Verdict.LICENSED);
        TrapDiagnostics licensed = new TrapDiagnostics(nodes::put, verdict::get);
        licensed.track(registry().acquire("dev-a", "0.0.0.0", 10162, licensed::refresh));
        licensed.accept(notification("1.3.6.1.6.3.1.1.5.3"));

        verdict.set(LicenseGate.Verdict.NOT_AVAILABLE);
        licensed.refresh();
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseNotAvailable,
                ((org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode) nodes.get("Last/Json")).getValue());
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseNotAvailable,
                ((org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode) nodes.get("Last/Sequence")).getValue());

        verdict.set(LicenseGate.Verdict.EXPIRED);
        licensed.refresh();
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseExpired,
                ((org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode) nodes.get("Last/Json")).getValue());

        verdict.set(LicenseGate.Verdict.LICENSED);
        licensed.refresh();
        assertEquals(1L, nodes.get("Last/Sequence"));
        assertTrue(text("Last/Json").startsWith("{\"sequence\":1,"));
    }

    private static final com.jw.snmpdriver.snmp.address.TrapItemPath COUNT_PATH =
            new com.jw.snmpdriver.snmp.address.TrapItemPath(com.jw.snmpdriver.snmp.address.TrapItemPath.Field.COUNT,
                    com.jw.snmpdriver.snmp.address.NumericOid.parse("1.3.6.1.6.3.1.1.5.3"));

    private long quality(String name) {
        return nodes.get(name) instanceof org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode code
                ? code.getValue() : 0L;
    }

    @Test
    void lastNodesFollowLicenseWhileBindFailureKeepsStatusDegraded() {
        java.util.concurrent.atomic.AtomicReference<LicenseGate.Verdict> verdict =
                new java.util.concurrent.atomic.AtomicReference<>(LicenseGate.Verdict.NOT_AVAILABLE);
        TrapDiagnostics licensed = new TrapDiagnostics(nodes::put, verdict::get);
        failures = 1;
        licensed.track(registry().acquire("dev-a", "0.0.0.0", 162, licensed::refresh));

        assertEquals("Degraded", nodes.get("Status"));
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseNotAvailable, quality("Last/Json"));
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseNotAvailable, quality("Last/Sequence"));

        verdict.set(LicenseGate.Verdict.EXPIRED);
        licensed.refresh();
        assertEquals("Degraded", nodes.get("Status"));
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseExpired, quality("Last/Json"));

        verdict.set(LicenseGate.Verdict.LICENSED);
        licensed.refresh();
        assertEquals("Degraded", nodes.get("Status"));
        assertEquals("", nodes.get("Last/Json"));
    }

    @Test
    void lastNodesFollowLicenseOnRejectedHandle() {
        java.util.concurrent.atomic.AtomicReference<LicenseGate.Verdict> verdict =
                new java.util.concurrent.atomic.AtomicReference<>(LicenseGate.Verdict.NOT_AVAILABLE);
        TrapDiagnostics licensed = new TrapDiagnostics(nodes::put, verdict::get);
        TrapListenerRegistry registry = registry();
        registry.acquire("dev-a", "0.0.0.0", 10162);
        licensed.track(registry.acquire("dev-b", "127.0.0.1", 10162, licensed::refresh));

        assertEquals("Degraded", nodes.get("Status"));
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseNotAvailable, quality("Last/Json"));
        assertEquals(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_LicenseNotAvailable,
                licensed.readTrapPath(COUNT_PATH).getStatusCode().getValue());

        verdict.set(LicenseGate.Verdict.LICENSED);
        licensed.refresh();
        assertEquals("Degraded", nodes.get("Status"));
        assertEquals("", nodes.get("Last/Json"));
        assertTrue(licensed.readTrapPath(COUNT_PATH).getStatusCode().isGood());
    }

    @Test
    void localEngineIdIsShownAndItsNoticeSurvivesStatusChanges() {
        diagnostics.showEngineIdentity("80001f01", "检测到 Gateway 被克隆");
        assertEquals("80001f01", nodes.get("LocalEngineId"));

        diagnostics.showDisabled();
        assertEquals("检测到 Gateway 被克隆", nodes.get("LastError"));

        diagnostics.showUnsupported("USM 不支持");
        assertEquals("USM 不支持 检测到 Gateway 被克隆", nodes.get("LastError"));
    }

    @Test
    void localEngineIdWithoutNoticeLeavesLastErrorEmpty() {
        diagnostics.showEngineIdentity("80001f01", null);
        diagnostics.showDisabled();

        assertEquals("80001f01", nodes.get("LocalEngineId"));
        assertEquals("", nodes.get("LastError"));
    }
}
