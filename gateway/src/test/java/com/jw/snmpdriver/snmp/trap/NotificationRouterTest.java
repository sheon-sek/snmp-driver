package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

class NotificationRouterTest {
    private static final TrapFilter.HostLookup NO_DNS = host -> {
        throw new UnknownHostException(host);
    };

    private volatile LicenseGate.Verdict verdict = LicenseGate.Verdict.LICENSED;
    private final NotificationRouter router = new NotificationRouter(10162, () -> verdict);
    private final Device sentinel = new Device("sentinel", "10.9.9.9", "public");

    @BeforeEach
    void start() {
        router.add(sentinel.recipient);
        router.start();
    }

    @AfterEach
    void stop() {
        router.close();
    }

    /** One worker delivers in arrival order, so once the sentinel arrives everything before it is done. */
    private void route(IncomingNotification notification) throws Exception {
        router.onNotification(notification);
        int before = sentinel.received.size();
        router.onNotification(incoming("10.9.9.9", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.1"), TrapNotification.Version.V2C));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sentinel.received.size() == before && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static final class Device {
        final TrapCounters counters = new TrapCounters();
        final List<TrapNotification> received = new CopyOnWriteArrayList<>();
        final List<String> notices = new ArrayList<>();
        final TrapRecipient recipient;

        Device(String name, String host, String community) {
            recipient = new TrapRecipient(name, new TrapFilter(host, "", bytes(community), NO_DNS),
                    counters, new TokenBucket(1000), received::add, notices::add);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static InetAddress ip(String text) throws UnknownHostException {
        return InetAddress.getByName(text);
    }

    private static PDU v2Trap(int type, String trapOid) {
        PDU pdu = new PDU();
        pdu.setType(type);
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.3.0"), new TimeTicks(500)));
        pdu.add(new VariableBinding(new OID("1.3.6.1.6.3.1.1.4.1.0"), new OID(trapOid)));
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.8.1"), new Integer32(2)));
        return pdu;
    }

    private IncomingNotification incoming(String source, String community, PDU pdu,
                                          TrapNotification.Version version) throws Exception {
        return new IncomingNotification(ip(source), 40000, version, bytes(community), pdu);
    }

    @Test
    void v2cTrapReachesTheOwningDeviceOnly() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        Device b = new Device("b", "10.0.0.2", "public");
        router.add(a.recipient);
        router.add(b.recipient);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, a.received.size());
        assertEquals(0, b.received.size());
        TrapNotification n = a.received.get(0);
        assertEquals(TrapNotification.Version.V2C, n.version());
        assertEquals(TrapNotification.PduType.V2_TRAP, n.pduType());
        assertEquals("1.3.6.1.6.3.1.1.5.3", n.trapOid());
        assertEquals(500, n.agentUpTime());
        assertEquals(3, n.varbinds().size());
        assertNull(n.agentAddress());
        assertEquals(1, a.counters.received());
        assertEquals(0, b.counters.received());
    }

    @Test
    void informIsRoutedAsInformPduType() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.INFORM, "1.3.6.1.6.3.1.1.5.4"), TrapNotification.Version.V2C));

        assertEquals(TrapNotification.PduType.INFORM, a.received.get(0).pduType());
    }

    @Test
    void v1TrapIsConvertedAndKeepsAgentAddressAsDisplayField() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        PDUv1 pdu = new PDUv1();
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9999"));
        pdu.setAgentAddress(new IpAddress("192.0.2.99"));
        pdu.setGenericTrap(6);
        pdu.setSpecificTrap(5);
        pdu.setTimestamp(77);

        route(incoming("10.0.0.1", "public", pdu, TrapNotification.Version.V1));

        TrapNotification n = a.received.get(0);
        assertEquals(TrapNotification.PduType.V1_TRAP, n.pduType());
        assertEquals("1.3.6.1.4.1.9999.0.5", n.trapOid());
        assertEquals("192.0.2.99", n.agentAddress());
        assertEquals(77, n.agentUpTime());
        assertEquals("10.0.0.1", n.source().getHostAddress());
    }

    @Test
    void agentAddrInsideV1PduNeverDecidesRouting() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        PDUv1 pdu = new PDUv1();
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9999"));
        pdu.setAgentAddress(new IpAddress("10.0.0.1"));
        pdu.setGenericTrap(0);

        route(incoming("203.0.113.9", "public", pdu, TrapNotification.Version.V1));

        assertEquals(0, a.received.size());
        assertEquals(1, router.unmatchedCount());
    }

    @Test
    void unknownSourceOnlyCountsUnmatched() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);

        route(incoming("198.51.100.4", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, router.unmatchedCount());
        assertEquals(0, a.counters.received());
        assertEquals(0, a.counters.authFailure());
    }

    @Test
    void wrongCommunityCountsAuthFailureAndHintsAtTrapCommunity() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);

        route(incoming("10.0.0.1", "wrong",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, a.counters.authFailure());
        assertEquals(0, a.counters.received());
        assertEquals(0, a.received.size());
        assertEquals(1, a.notices.size());
        assertTrue(a.notices.get(0).contains("Trap Community"));
        assertTrue(!a.notices.get(0).contains("wrong"), "the presented community must not leak");
    }

    @Test
    void sourceSharedByTwoDevicesBroadcastsToBothAndCountsMultiMatch() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        Device b = new Device("b", "10.0.0.1", "other");
        router.add(a.recipient);
        router.add(b.recipient);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, a.received.size());
        assertEquals(0, b.received.size());
        assertEquals(1, b.counters.authFailure());
        assertEquals(1, router.multiMatchCount());
    }

    @Test
    void sameCommunityOnSharedSourceReachesEveryMatchingDevice() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        Device b = new Device("b", "10.0.0.1", "public");
        router.add(a.recipient);
        router.add(b.recipient);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, a.received.size());
        assertEquals(1, b.received.size());
    }

    @Test
    void v2PduWithoutLeadingSysUpTimeAndTrapOidIsIgnored() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.1.0"), new OctetString("x")));

        route(incoming("10.0.0.1", "public", pdu, TrapNotification.Version.V2C));

        assertEquals(0, a.received.size());
        assertEquals(0, a.counters.received());
    }

    @Test
    void failingSinkNeverEscapesToTheTransportThread() throws Exception {
        TrapRecipient bad = new TrapRecipient("bad", new TrapFilter("10.0.0.1", "", bytes("public"), NO_DNS),
                new TrapCounters(), new TokenBucket(1000), n -> {
                    throw new IllegalStateException("boom");
                }, s -> { });
        router.add(bad);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));
    }

    @Test
    void failingDeviceCallbackDoesNotStopTheBroadcastToOthers() throws Exception {
        TrapRecipient bad = new TrapRecipient("bad", new TrapFilter("10.0.0.1", "", bytes("public"), NO_DNS),
                new TrapCounters(), new TokenBucket(1000), n -> {
                    throw new IllegalStateException("boom");
                }, s -> { });
        Device good = new Device("good", "10.0.0.1", "public");
        router.add(bad);
        router.add(good.recipient);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, good.received.size());
        assertEquals(1, good.counters.received());
    }

    @Test
    void failingAuthNoticeDoesNotStopTheBroadcastToOthers() throws Exception {
        TrapRecipient bad = new TrapRecipient("bad", new TrapFilter("10.0.0.1", "", bytes("other"), NO_DNS),
                new TrapCounters(), new TokenBucket(1000), n -> { }, s -> {
                    throw new IllegalStateException("boom");
                });
        Device good = new Device("good", "10.0.0.1", "public");
        router.add(bad);
        router.add(good.recipient);

        route(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, good.received.size());
    }

    @Test
    void v2cCommunityVarbindIsNotHandedToDevices() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        PDU pdu = v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3");
        pdu.add(new VariableBinding(V1TrapNormalizer.SNMP_TRAP_COMMUNITY, new OctetString("secret")));

        route(incoming("10.0.0.1", "public", pdu, TrapNotification.Version.V2C));

        assertEquals(3, a.received.get(0).varbinds().size());
        for (VariableBinding vb : a.received.get(0).varbinds()) {
            assertTrue(!vb.getOid().equals(V1TrapNormalizer.SNMP_TRAP_COMMUNITY));
        }
    }

    private IncomingNotification fromA() throws Exception {
        return incoming("10.0.0.1", "public", v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C);
    }

    @Test
    void unlicensedDropsWithoutQueuingAndRecoversWithoutRestart() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        verdict = LicenseGate.Verdict.NOT_AVAILABLE;

        route(fromA());

        assertEquals(0, a.received.size());
        assertEquals(1, a.counters.received(), "ReceivedCount includes dropped Notifications");
        assertEquals(1, a.counters.droppedUnlicensed());
        assertEquals(0, a.counters.accepted());

        verdict = LicenseGate.Verdict.LICENSED;
        route(fromA());

        assertEquals(1, a.received.size());
        assertEquals(1, a.counters.accepted());
        assertEquals(1, a.counters.droppedUnlicensed());
    }

    @Test
    void expiredLicenseAlsoDrops() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        verdict = LicenseGate.Verdict.EXPIRED;

        route(fromA());

        assertEquals(1, a.counters.droppedUnlicensed());
    }

    @Test
    void burstBeyondTwiceTheRateIsDroppedAndOtherDevicesAreUnaffected() throws Exception {
        long[] clock = {0};
        TrapCounters limitedCounters = new TrapCounters();
        List<TrapNotification> limitedReceived = new CopyOnWriteArrayList<>();
        router.add(new TrapRecipient("limited", new TrapFilter("10.0.0.3", "", bytes("public"), NO_DNS),
                limitedCounters, new TokenBucket(2, () -> clock[0]), limitedReceived::add, s -> { }));
        Device free = new Device("free", "10.0.0.4", "public");
        router.add(free.recipient);

        for (int i = 0; i < 6; i++) {
            router.onNotification(incoming("10.0.0.3", "public",
                    v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));
            router.onNotification(incoming("10.0.0.4", "public",
                    v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));
        }
        route(fromA());

        assertEquals(6, limitedCounters.received());
        assertEquals(2, limitedCounters.droppedRateLimit(), "capacity is twice the rate (4 of 6 pass)");
        assertEquals(4, limitedReceived.size());
        assertEquals(0, free.counters.droppedRateLimit());
        assertEquals(6, free.received.size());
    }

    @Test
    void fullQueueCountsDropsAndNeverBlocksTheTransportThread() throws Exception {
        NotificationRouter tiny = new NotificationRouter(10163, () -> LicenseGate.Verdict.LICENSED, 2);
        CountDownLatch release = new CountDownLatch(1);
        TrapCounters counters = new TrapCounters();
        List<TrapNotification> delivered = new CopyOnWriteArrayList<>();
        tiny.add(new TrapRecipient("slow", new TrapFilter("10.0.0.1", "", bytes("public"), NO_DNS), counters,
                new TokenBucket(1000), n -> {
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    delivered.add(n);
                }, s -> { }));
        tiny.start();
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                tiny.onNotification(fromA());
            }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "offer must not block");

            assertEquals(20, counters.received());
            // one is held by the blocked worker, two fit in the queue
            assertTrue(counters.droppedQueueFull() >= 17 && counters.droppedQueueFull() <= 18,
                    "queue-full drops: " + counters.droppedQueueFull());
        } finally {
            release.countDown();
            tiny.close();
        }
    }

    @Test
    void notificationsQueuedForARemovedDeviceAreDroppedSilently() throws Exception {
        NotificationRouter local = new NotificationRouter(10164, () -> LicenseGate.Verdict.LICENSED, 10);
        CountDownLatch gate = new CountDownLatch(1);
        TrapCounters stale = new TrapCounters();
        List<TrapNotification> staleReceived = new CopyOnWriteArrayList<>();
        TrapRecipient blocker = new TrapRecipient("blocker",
                new TrapFilter("10.0.0.2", "", bytes("public"), NO_DNS), new TrapCounters(), new TokenBucket(1000),
                n -> {
                    try {
                        gate.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }, s -> { });
        TrapRecipient gone = new TrapRecipient("gone", new TrapFilter("10.0.0.1", "", bytes("public"), NO_DNS),
                stale, new TokenBucket(1000), staleReceived::add, s -> { });
        local.add(blocker);
        local.add(gone);
        local.start();
        try {
            local.onNotification(incoming("10.0.0.2", "public",
                    v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));
            local.onNotification(fromA());
            local.remove(gone);
            gate.countDown();
            Thread.sleep(200);

            assertEquals(0, staleReceived.size());
            assertEquals(0, stale.accepted());
            assertEquals(0, stale.droppedQueueFull() + stale.droppedRateLimit() + stale.droppedUnlicensed());
        } finally {
            gate.countDown();
            local.close();
        }
    }

    @Test
    void varbindsBeyondTheCapAreTruncatedAndFlagged() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);
        PDU pdu = v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3");
        for (int i = 0; i < 200; i++) {
            pdu.add(new VariableBinding(new OID("1.3.6.1.4.1.9999.1." + i), new Integer32(i)));
        }

        route(incoming("10.0.0.1", "public", pdu, TrapNotification.Version.V2C));

        TrapNotification n = a.received.get(0);
        assertEquals(TrapNotification.MAX_VARBINDS, n.varbinds().size());
        assertTrue(n.varbindsTruncated());
        assertEquals("1.3.6.1.6.3.1.1.5.3", n.trapOid());
    }
}
