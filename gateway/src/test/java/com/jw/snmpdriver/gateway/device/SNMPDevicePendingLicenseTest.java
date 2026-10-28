package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import com.jw.ignition.license.enums.LicenseStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** License admission through the real listener, Device nodes and Record handler boundary. */
class SNMPDevicePendingLicenseTest {
    @Test
    void pendingTrapsWaitThenProduceRecordsInArrivalOrderWhenLicensed() throws Exception {
        List<String> records = new CopyOnWriteArrayList<>();
        try (SNMPDeviceFixture fixture = fixture(records)) {
            fixture.pendingLicense();
            for (int i = 1; i <= 3; i++) {
                send(fixture.trapPort, i, false);
                int received = i;
                await(() -> count(fixture, "ReceivedCount") == received);
            }
            assertEquals(0, count(fixture, "AcceptedCount"));
            assertEquals(0, count(fixture, "DroppedUnlicensedCount"));
            assertTrue(records.isEmpty());
            assertEquals("Listening", fixture.value("Status"));

            fixture.license(true);
            send(fixture.trapPort, 4, false);
            await(() -> count(fixture, "RecordDeliveredCount") == 4);
            assertEquals(4, count(fixture, "AcceptedCount"));
            assertEquals(0, count(fixture, "DroppedUnlicensedCount"));
            for (int i = 1; i <= 4; i++) {
                assertTrue(records.get(i - 1).contains("\"sequence\":" + i + ","), records.toString());
                assertTrue(records.get(i - 1).contains("\"trapOid\":\"1.3.6.1.6.3.1.1.5." + i + "\""),
                        records.toString());
            }
            assertEquals(4, count(fixture, "ReceivedCount"));
            assertReconciled(fixture);
        }
    }

    @Test
    void pendingInformIsNotAcknowledgedOrStoredAndRetryIsAcceptedOnlyAfterLicenseVerdict() throws Exception {
        List<String> records = new CopyOnWriteArrayList<>();
        try (SNMPDeviceFixture fixture = fixture(records);
             Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            PDU inform = pdu(1, true);
            CommunityTarget<Address> target = target(fixture.trapPort);
            fixture.pendingLicense();
            var pending = snmp.send(inform, target);
            assertTrue(pending == null || pending.getResponse() == null);
            await(() -> count(fixture, "ReceivedCount") == 1);
            assertEquals(0, count(fixture, "DroppedUnlicensedCount"));
            await(() -> count(fixture, "PendingInformCount") == 1);
            assertEquals(0, count(fixture, "AcceptedCount"));
            fixture.license(true);
            Thread.sleep(200);
            assertTrue(records.isEmpty(), "PENDING Inform must not be stored for later delivery");
            // Same source port and request ID: PENDING must not poison the Inform dedup cache.
            assertTrue(snmp.send(inform, target).getResponse() != null);
            await(() -> count(fixture, "RecordDeliveredCount") == 1);
            assertTrue(snmp.send(inform, target).getResponse() != null);
            await(() -> count(fixture, "DuplicateInformCount") == 1);
            assertEquals(3, count(fixture, "ReceivedCount"));
            assertEquals(1, count(fixture, "AcceptedCount"));
            assertEquals(1, count(fixture, "PendingInformCount"));
            assertEquals(0, count(fixture, "DroppedUnlicensedCount"));
            assertEquals(1, records.size());
            assertReconciled(fixture);
        }
    }

    @ParameterizedTest
    @EnumSource(value = LicenseStatusCode.class, names = {"MISSING_FILE", "EXPIRED", "IO_ERROR"})
    void invalidLicenseDiscardsAllStagedTrapsWithoutProducingRecords(LicenseStatusCode verdict) throws Exception {
        List<String> records = new CopyOnWriteArrayList<>();
        try (SNMPDeviceFixture fixture = fixture(records)) {
            fixture.pendingLicense();
            for (int i = 1; i <= 3; i++) {
                send(fixture.trapPort, i, false);
            }
            await(() -> count(fixture, "ReceivedCount") == 3);
            assertEquals(0, count(fixture, "DroppedUnlicensedCount"));
            fixture.license(verdict);
            await(() -> count(fixture, "DroppedUnlicensedCount") == 3);
            assertEquals(0, count(fixture, "AcceptedCount"));
            assertEquals(0, count(fixture, "RecordQueueDepth"));
            assertEquals(0, count(fixture, "RecordDeliveredCount"));
            assertTrue(records.isEmpty());
            assertReconciled(fixture);
            fixture.license(true);
            send(fixture.trapPort, 4, false);
            await(() -> count(fixture, "RecordDeliveredCount") == 1);
            assertEquals(1, records.size(), "discarded Traps must never replay on recovery");
            assertReconciled(fixture);
        }
    }

    @Test
    void pendingTrapsShareTheExistingBoundedQueueAndOverflowCountsOnlyNewArrivals() throws Exception {
        List<String> records = new CopyOnWriteArrayList<>();
        try (SNMPDeviceFixture fixture = fixture(records);
             Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            fixture.pendingLicense();
            snmp.listen();
            CommunityTarget<Address> target = target(fixture.trapPort);
            // Batch observations avoid UDP socket loss; the Device exposes merged counters once per second.
            for (int i = 1; i <= 10_001; i++) {
                snmp.send(pdu(i, false), target);
                Thread.sleep(1);
                if (i % 1000 == 0 || i == 10_001) {
                    int received = i;
                    await(() -> count(fixture, "ReceivedCount") == received);
                }
            }
            await(() -> count(fixture, "DroppedQueueFullCount") == 1);
            assertEquals(0, count(fixture, "DroppedUnlicensedCount"));
            assertEquals(0, count(fixture, "AcceptedCount"));
            assertTrue(((String) fixture.value("LastError")).contains("队列已满"));
            assertEquals("Listening", fixture.value("Status"));
            assertTrue(records.isEmpty());
            fixture.license(false);
            await(() -> count(fixture, "DroppedUnlicensedCount") == 10_000);
            assertReconciled(fixture);
            assertTrue(records.isEmpty());
        }
    }

    private static void assertReconciled(SNMPDeviceFixture fixture) {
        assertEquals(count(fixture, "ReceivedCount"),
                count(fixture, "AcceptedCount") + count(fixture, "DroppedUnlicensedCount")
                        + count(fixture, "DroppedQueueFullCount") + count(fixture, "DroppedRateLimitCount")
                        + count(fixture, "DuplicateInformCount") + count(fixture, "PendingInformCount"));
    }

    private static SNMPDeviceFixture fixture(List<String> records) throws Exception {
        return new SNMPDeviceFixture("snmp.handlers.onNotification", (project, function, record) -> {
            records.add(record);
            return RecordHandlerInvoker.Result.SUCCESS;
        });
    }

    private static long count(SNMPDeviceFixture fixture, String name) {
        return ((Number) fixture.value(name)).longValue();
    }

    private static PDU pdu(int index, boolean inform) {
        PDU pdu = new PDU();
        pdu.setType(inform ? PDU.INFORM : PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(900)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5." + index)));
        return pdu;
    }

    private static CommunityTarget<Address> target(int port) {
        CommunityTarget<Address> target = new CommunityTarget<>();
        target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
        target.setCommunity(new OctetString("public"));
        target.setVersion(SnmpConstants.version2c);
        target.setTimeout(200);
        target.setRetries(0);
        return target;
    }

    private static boolean send(int port, int index, boolean inform) throws Exception {
        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            var response = snmp.send(pdu(index, inform), target(port));
            return response != null && response.getResponse() != null;
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for diagnostics");
            Thread.sleep(25);
        }
    }
}
