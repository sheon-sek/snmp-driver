package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** Loopback v2c Traps into a real SNMPDevice on a hand-made Gateway, asserting the published Traps/ nodes. */
class SNMPDeviceTrapNodesTest {
    private static final List<String> COUNTERS = List.of("ReceivedCount", "AcceptedCount", "DroppedRateLimitCount",
            "DroppedQueueFullCount", "DroppedUnlicensedCount", "AuthFailureCount", "DuplicateInformCount",
            "ListenerUnmatchedCount", "ListenerMultiMatchCount");
    private static final List<String> LAST = List.of("Sequence", "ReceivedTime", "PduType", "Version", "TrapOid",
            "Source", "AgentAddress", "AgentUpTime", "Varbinds", "Json");

    private SNMPDeviceFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static void sendV2cTrap(int port, String community, String trapOid, int extraValue) throws Exception {
        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(900)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID(trapOid)));
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.3"), new Integer32(extraValue)));
        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            CommunityTarget<org.snmp4j.smi.Address> target = new CommunityTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setCommunity(new OctetString(community));
            target.setVersion(SnmpConstants.version2c);
            snmp.send(pdu, target);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private static boolean sequenceIs(SNMPDeviceFixture f, long expected) {
        // Last/Json is written last, so once it carries the sequence every mirror node is in place
        return f.value("Last/Json") instanceof String json && json.startsWith("{\"sequence\":" + expected + ",");
    }

    @Test
    void trapsFolderIsCompleteAndEmptyBeforeTheFirstNotification() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);

        assertEquals(Unsigned.ulong(0), fixture.value("Last/Sequence"));
        assertEquals(DateTime.NULL_VALUE, fixture.value("Last/ReceivedTime"));
        assertEquals("", fixture.value("Last/Json"));
        assertEquals("", fixture.value("Last/TrapOid"));
        assertEquals(Unsigned.uint(0), fixture.value("Last/AgentUpTime"));
        for (String name : COUNTERS) {
            assertEquals(Unsigned.ulong(0), fixture.value(name), name);
        }
        for (String name : LAST) {
            assertTrue(fixture.read("Last/" + name).getStatusCode().isGood(), name);
        }
    }

    @Test
    void v2cTrapPublishesLastNodesJsonAndCountersAndSequenceIncrements() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);
        await("listener Listening", () -> "Listening".equals(fixture.value("Status")));

        sendV2cTrap(fixture.trapPort, "public", "1.3.6.1.6.3.1.1.5.3", 3);
        await("Sequence 1", () -> sequenceIs(fixture, 1));

        assertEquals("V2_TRAP", fixture.value("Last/PduType"));
        assertEquals("V2C", fixture.value("Last/Version"));
        assertEquals("1.3.6.1.6.3.1.1.5.3", fixture.value("Last/TrapOid"));
        assertTrue(((String) fixture.value("Last/Source")).startsWith("127.0.0.1:"));
        assertEquals("", fixture.value("Last/AgentAddress"));
        assertEquals(Unsigned.uint(900), fixture.value("Last/AgentUpTime"));
        assertTrue(fixture.value("Last/ReceivedTime") instanceof DateTime t && !t.isNull());
        String varbinds = (String) fixture.value("Last/Varbinds");
        assertEquals("[{\"oid\":\"1.3.6.1.2.1.1.3.0\",\"type\":\"TimeTicks\",\"value\":\"0:00:09.00\"},"
                + "{\"oid\":\"1.3.6.1.6.3.1.1.4.1.0\",\"type\":\"OBJECT IDENTIFIER\",\"value\":\"1.3.6.1.6.3.1.1.5.3\"},"
                + "{\"oid\":\"1.3.6.1.2.1.2.2.1.1.3\",\"type\":\"Integer32\",\"value\":\"3\"}]", varbinds);
        String json = (String) fixture.value("Last/Json");
        assertTrue(json.startsWith("{\"sequence\":1,\"receivedTime\":\""), json);
        assertTrue(json.contains("\"pduType\":\"V2_TRAP\",\"version\":\"V2C\","
                + "\"trapOid\":\"1.3.6.1.6.3.1.1.5.3\",\"source\":\"127.0.0.1:"), json);
        assertTrue(json.endsWith(",\"agentAddress\":\"\",\"agentUpTime\":900,\"varbinds\":" + varbinds + "}"), json);

        sendV2cTrap(fixture.trapPort, "public", "1.3.6.1.6.3.1.1.5.4", 4);
        await("Sequence 2", () -> sequenceIs(fixture, 2));
        assertEquals("1.3.6.1.6.3.1.1.5.4", fixture.value("Last/TrapOid"));
        assertTrue(((String) fixture.value("Last/Json")).startsWith("{\"sequence\":2,"));

        await("counters", () -> Unsigned.ulong(2).equals(fixture.value("AcceptedCount")));
        assertEquals(Unsigned.ulong(2), fixture.value("ReceivedCount"));
        assertEquals(Unsigned.ulong(0), fixture.value("AuthFailureCount"));
    }

    @Test
    void wrongCommunityCountsAuthFailureAndLeavesLastUntouched() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);

        sendV2cTrap(fixture.trapPort, "wrong", "1.3.6.1.6.3.1.1.5.3", 1);
        await("AuthFailureCount", () -> Unsigned.ulong(1).equals(fixture.value("AuthFailureCount")));

        assertEquals(Unsigned.ulong(0), fixture.value("Last/Sequence"));
        assertEquals(Unsigned.ulong(0), fixture.value("AcceptedCount"));
    }

    @Test
    void lastNodesAreBadWithoutLicenseAndRecoverWithoutRestart() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", false);

        await("Status Unlicensed", () -> "Unlicensed".equals(fixture.value("Status")));
        for (String name : LAST) {
            assertEquals(StatusCodes.Bad_LicenseNotAvailable, fixture.read("Last/" + name).getStatusCode().getValue(), name);
        }
        sendV2cTrap(fixture.trapPort, "public", "1.3.6.1.6.3.1.1.5.3", 1);
        await("DroppedUnlicensedCount", () -> Unsigned.ulong(1).equals(fixture.value("DroppedUnlicensedCount")));

        fixture.license(true);
        await("Listening again", () -> "Listening".equals(fixture.value("Status")));
        for (String name : LAST) {
            assertTrue(fixture.read("Last/" + name).getStatusCode().isGood(), name);
        }
        sendV2cTrap(fixture.trapPort, "public", "1.3.6.1.6.3.1.1.5.3", 1);
        await("Sequence 1", () -> sequenceIs(fixture, 1));
    }

    @Test
    void disabledTrapKeepsEveryNodeAndShowsDisabled() throws Exception {
        fixture = new SNMPDeviceFixture(false, "public", true);

        assertEquals("Disabled", fixture.value("Status"));
        assertEquals("", fixture.value("LastError"));
        assertEquals("", fixture.value("ListenerEndpoint"));
        for (String name : COUNTERS) {
            assertEquals(Unsigned.ulong(0), fixture.value(name), name);
        }
        for (String name : LAST) {
            assertTrue(fixture.read("Last/" + name).getStatusCode().isGood(), name);
        }
        assertEquals(Unsigned.ulong(0), fixture.value("Last/Sequence"));
    }
}
