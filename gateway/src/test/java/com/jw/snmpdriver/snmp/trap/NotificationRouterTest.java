package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
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

    private final NotificationRouter router = new NotificationRouter(10162);

    private static final class Device {
        final TrapCounters counters = new TrapCounters();
        final List<TrapNotification> received = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        final TrapRecipient recipient;

        Device(String name, String host, String community) {
            recipient = new TrapRecipient(name, new TrapFilter(host, "", bytes(community), NO_DNS),
                    counters, received::add, notices::add);
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

        router.onNotification(incoming("10.0.0.1", "public",
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

        router.onNotification(incoming("10.0.0.1", "public",
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

        router.onNotification(incoming("10.0.0.1", "public", pdu, TrapNotification.Version.V1));

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

        router.onNotification(incoming("203.0.113.9", "public", pdu, TrapNotification.Version.V1));

        assertEquals(0, a.received.size());
        assertEquals(1, router.unmatchedCount());
    }

    @Test
    void unknownSourceOnlyCountsUnmatched() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);

        router.onNotification(incoming("198.51.100.4", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));

        assertEquals(1, router.unmatchedCount());
        assertEquals(0, a.counters.received());
        assertEquals(0, a.counters.authFailure());
    }

    @Test
    void wrongCommunityCountsAuthFailureAndHintsAtTrapCommunity() throws Exception {
        Device a = new Device("a", "10.0.0.1", "public");
        router.add(a.recipient);

        router.onNotification(incoming("10.0.0.1", "wrong",
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

        router.onNotification(incoming("10.0.0.1", "public",
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

        router.onNotification(incoming("10.0.0.1", "public",
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

        router.onNotification(incoming("10.0.0.1", "public", pdu, TrapNotification.Version.V2C));

        assertEquals(0, a.received.size());
        assertEquals(0, a.counters.received());
    }

    @Test
    void failingSinkNeverEscapesToTheTransportThread() throws Exception {
        TrapRecipient bad = new TrapRecipient("bad", new TrapFilter("10.0.0.1", "", bytes("public"), NO_DNS),
                new TrapCounters(), n -> {
                    throw new IllegalStateException("boom");
                }, s -> { });
        router.add(bad);

        router.onNotification(incoming("10.0.0.1", "public",
                v2Trap(PDU.TRAP, "1.3.6.1.6.3.1.1.5.3"), TrapNotification.Version.V2C));
    }
}
