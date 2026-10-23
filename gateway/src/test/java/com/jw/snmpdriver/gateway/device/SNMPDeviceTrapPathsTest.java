package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
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

/** Loopback v2c Traps into a real SNMPDevice, read and subscribed through the dynamic TRAP.* Item Paths. */
class SNMPDeviceTrapPathsTest {
    private static final String LINK_DOWN = "1.3.6.1.6.3.1.1.5.3";
    private static final String LINK_UP = "1.3.6.1.6.3.1.1.5.4";

    private SNMPDeviceFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static void sendV2cTraps(int port, String... trapOids) throws Exception {
        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            CommunityTarget<org.snmp4j.smi.Address> target = new CommunityTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setCommunity(new OctetString("public"));
            target.setVersion(SnmpConstants.version2c);
            for (String trapOid : trapOids) {
                PDU pdu = new PDU();
                pdu.setType(PDU.TRAP);
                pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(900)));
                pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID(trapOid)));
                pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.3"), new Integer32(3)));
                snmp.send(pdu, target);
            }
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

    private static Object value(DataValue read) {
        return read.getValue().getValue();
    }

    private void awaitSequence(long sequence) throws Exception {
        await("Sequence " + sequence, () -> Unsigned.ulong(sequence).equals(fixture.value("Last/Sequence")));
    }

    @Test
    void trapPathsReadPerOidCountSequenceLastTimeAndVarbinds() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);
        await("listener Listening", () -> "Listening".equals(fixture.value("Status")));

        sendV2cTraps(fixture.trapPort, LINK_DOWN, LINK_UP, LINK_DOWN);
        awaitSequence(3);

        assertEquals(Unsigned.ulong(2), value(fixture.readPath("TRAP.Count/" + LINK_DOWN)));
        assertEquals(Unsigned.ulong(1), value(fixture.readPath("TRAP.Count/" + LINK_UP)));
        assertEquals(Unsigned.ulong(3), value(fixture.readPath("TRAP.Sequence/" + LINK_DOWN)));
        assertEquals(Unsigned.ulong(2), value(fixture.readPath("TRAP.Sequence/" + LINK_UP)));
        assertTrue(value(fixture.readPath("TRAP.LastTime/" + LINK_DOWN)) instanceof DateTime t && !t.isNull());
        assertEquals(value(fixture.readPath("TRAP.LastVarbinds/" + LINK_DOWN)), fixture.value("Last/Varbinds"));
        assertTrue(fixture.readPath("TRAP.Count/" + LINK_DOWN).getStatusCode().isGood());
        // the leading dot and the lower-case prefix name the same OID
        assertEquals(Unsigned.ulong(2), value(fixture.readPath("trap.count/." + LINK_DOWN)));
    }

    @Test
    void anUntrackedOidReadsCountZeroWithGoodQualityAndEmptyFields() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);

        DataValue count = fixture.readPath("TRAP.Count/1.3.6.1.4.1.99.1");
        assertTrue(count.getStatusCode().isGood());
        assertEquals(Unsigned.ulong(0), value(count));
        assertEquals(Unsigned.ulong(0), value(fixture.readPath("TRAP.Sequence/1.3.6.1.4.1.99.1")));
        DataValue lastTime = fixture.readPath("TRAP.LastTime/1.3.6.1.4.1.99.1");
        assertTrue(lastTime.getStatusCode().isGood());
        assertEquals(DateTime.NULL_VALUE, value(lastTime));
        DataValue varbinds = fixture.readPath("TRAP.LastVarbinds/1.3.6.1.4.1.99.1");
        assertTrue(varbinds.getStatusCode().isGood());
        assertEquals("", value(varbinds));
    }

    @Test
    void trapPathNodeAttributesAdvertiseTypeAndReadOnlyAccess() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);

        assertEquals(Identifiers.UInt64, value(fixture.readPath("TRAP.Count/" + LINK_DOWN, AttributeId.DataType)));
        assertEquals(Identifiers.DateTime, value(fixture.readPath("TRAP.LastTime/" + LINK_DOWN, AttributeId.DataType)));
        assertEquals(Identifiers.String, value(fixture.readPath("TRAP.LastVarbinds/" + LINK_DOWN, AttributeId.DataType)));
        assertEquals(Unsigned.ubyte(1), value(fixture.readPath("TRAP.Count/" + LINK_DOWN, AttributeId.AccessLevel)));
        String description = ((org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText)
                value(fixture.readPath("TRAP.Count/" + LINK_DOWN, AttributeId.Description))).getText();
        assertTrue(description.contains("Traps/Last/Json"), description);
    }

    @Test
    void invalidTrapPathIsBadNodeIdInvalid() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);

        assertEquals(StatusCodes.Bad_NodeIdInvalid,
                fixture.readPath("TRAP.Count/not-an-oid").getStatusCode().getValue());
        assertEquals(StatusCodes.Bad_NodeIdInvalid,
                fixture.readPath("TRAP.Bogus/" + LINK_DOWN).getStatusCode().getValue());
    }

    @Test
    void subscriptionBelowThePollingFloorGetsPushedOnEachNotification() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);
        await("listener Listening", () -> "Listening".equals(fixture.value("Status")));

        List<DataValue> count = fixture.subscribePath("TRAP.Count/" + LINK_DOWN, 100.0);
        List<DataValue> varbinds = fixture.subscribePath("TRAP.LastVarbinds/" + LINK_DOWN, 100.0);

        assertEquals(Unsigned.ulong(0), value(count.get(0)));
        assertTrue(count.get(0).getStatusCode().isGood());
        sendV2cTraps(fixture.trapPort, LINK_DOWN);
        await("pushed Count 1", () -> count.size() >= 2);
        assertEquals(Unsigned.ulong(1), value(count.get(count.size() - 1)));
        await("pushed varbinds", () -> varbinds.size() >= 2);
        assertEquals(fixture.value("Last/Varbinds"), value(varbinds.get(varbinds.size() - 1)));

        int before = count.size();
        sendV2cTraps(fixture.trapPort, LINK_UP);
        awaitSequence(2);
        assertEquals(before, count.size(), "another OID must not push this path");
    }

    @Test
    void trapPathsFollowTheLicenseVerdictAndRecover() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", false);
        await("Status Unlicensed", () -> "Unlicensed".equals(fixture.value("Status")));

        List<DataValue> count = fixture.subscribePath("TRAP.Count/" + LINK_DOWN, 1000.0);
        assertEquals(StatusCodes.Bad_LicenseNotAvailable, fixture.readPath("TRAP.Count/" + LINK_DOWN).getStatusCode().getValue());
        assertEquals(StatusCodes.Bad_LicenseNotAvailable, count.get(count.size() - 1).getStatusCode().getValue());

        fixture.license(true);
        await("Listening again", () -> "Listening".equals(fixture.value("Status")));
        assertTrue(fixture.readPath("TRAP.Count/" + LINK_DOWN).getStatusCode().isGood());
        await("subscriber recovers", () -> count.get(count.size() - 1).getStatusCode().isGood());

        sendV2cTraps(fixture.trapPort, LINK_DOWN);
        await("Count 1", () -> Unsigned.ulong(1).equals(value(fixture.readPath("TRAP.Count/" + LINK_DOWN))));
    }

    @Test
    void oidsBeyondTheLimitAreCountedInOverflowCountAndSubscribedPathsKeepTheirSlot() throws Exception {
        fixture = new SNMPDeviceFixture(true, "public", true);
        await("listener Listening", () -> "Listening".equals(fixture.value("Status")));
        String reserved = "1.3.6.1.4.1.99.9999";
        List<DataValue> subscribed = fixture.subscribePath("TRAP.Count/" + reserved, 1000.0);

        int total = 250;
        String[] oids = new String[total];
        for (int n = 0; n < total; n++) {
            oids[n] = "1.3.6.1.4.1.99." + n;
        }
        sendV2cTraps(fixture.trapPort, oids);
        awaitSequence(total);

        // the subscribed OID already holds one of the arrival-limit slots
        int arrivalSlots = TrapOidStates.MAX_TRACKED - TrapOidStates.RESERVED_FOR_SUBSCRIBED - 1;
        await("OverflowCount", () -> Unsigned.ulong(total - arrivalSlots).equals(fixture.value("OverflowCount")));
        assertEquals(Unsigned.ulong(1), value(fixture.readPath("TRAP.Count/" + oids[0])));
        assertEquals(Unsigned.ulong(0), value(fixture.readPath("TRAP.Count/" + oids[total - 1])));
        assertTrue(fixture.readPath("TRAP.Count/" + oids[total - 1]).getStatusCode().isGood());

        sendV2cTraps(fixture.trapPort, reserved);
        awaitSequence(total + 1);
        assertEquals(Unsigned.ulong(1), value(fixture.readPath("TRAP.Count/" + reserved)));
        await("pushed to the subscriber", () -> Unsigned.ulong(1).equals(value(subscribed.get(subscribed.size() - 1))));
    }
}
