package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.jw.snmpdriver.snmp.trap.TrapNotification;
import org.junit.jupiter.api.Test;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

class TrapStateTest {
    private static final Instant RECEIVED = Instant.parse("2026-01-02T03:04:05.678Z");

    private static TrapNotification notification(List<VariableBinding> varbinds, boolean truncated) throws Exception {
        return new TrapNotification(RECEIVED, TrapNotification.Version.V1, TrapNotification.PduType.V1_TRAP,
                InetAddress.getByName("192.0.2.7"), 40162, "192.0.2.9", 12345L, "1.3.6.1.6.3.1.1.5.3",
                varbinds, truncated);
    }

    private static List<VariableBinding> varbinds(VariableBinding... extra) {
        List<VariableBinding> list = new ArrayList<>();
        list.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(12345)));
        list.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        list.addAll(List.of(extra));
        return list;
    }

    @Test
    void startsWithSequenceZeroAndEmptyFields() {
        TrapState.Snapshot first = new TrapState().last();

        assertEquals(0L, first.sequence());
        assertEquals(null, first.receivedAt());
        assertEquals("", first.trapOid());
        assertEquals("", first.json());
    }

    @Test
    void recordJsonPutsDeviceFirstAndStaysValidForAnEmptySnapshot() throws Exception {
        assertEquals("{\"device\":\"d\\\"1\"}", TrapState.recordJson("d\"1", new TrapState().last()));

        TrapState.Snapshot snapshot = new TrapState().record(notification(varbinds(), false));
        assertEquals("{\"device\":\"d\"," + snapshot.json().substring(1), TrapState.recordJson("d", snapshot));
    }

    @Test
    void recordIncrementsSequenceAndRendersJson() throws Exception {
        TrapState state = new TrapState();
        state.record(notification(varbinds(), false));
        TrapState.Snapshot second = state.record(notification(varbinds(
                new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.3"), new Integer32(3)),
                new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"), new OctetString("a\"b\\c"))), false));

        assertEquals(2L, second.sequence());
        assertSame(second, state.last());
        assertEquals("V1_TRAP", second.pduType());
        assertEquals("V1", second.version());
        assertEquals("192.0.2.7:40162", second.source());
        assertEquals("192.0.2.9", second.agentAddress());
        assertEquals(12345L, second.agentUpTime());
        assertEquals("[{\"oid\":\"1.3.6.1.2.1.1.3.0\",\"type\":\"TimeTicks\",\"value\":\"0:02:03.45\"},"
                + "{\"oid\":\"1.3.6.1.6.3.1.1.4.1.0\",\"type\":\"OBJECT IDENTIFIER\",\"value\":\"1.3.6.1.6.3.1.1.5.3\"},"
                + "{\"oid\":\"1.3.6.1.2.1.2.2.1.1.3\",\"type\":\"Integer32\",\"value\":\"3\"},"
                + "{\"oid\":\"1.3.6.1.2.1.1.5.0\",\"type\":\"OCTET STRING\",\"value\":\"a\\\"b\\\\c\"}]",
                second.varbindsJson());
        assertEquals("{\"sequence\":2,\"receivedTime\":\"2026-01-02T03:04:05.678Z\",\"pduType\":\"V1_TRAP\","
                + "\"version\":\"V1\",\"trapOid\":\"1.3.6.1.6.3.1.1.5.3\",\"source\":\"192.0.2.7:40162\","
                + "\"agentAddress\":\"192.0.2.9\",\"agentUpTime\":12345,\"varbinds\":" + second.varbindsJson() + "}",
                second.json());
    }

    @Test
    void oversizedVarbindsAreCutAtSixteenKiBAndMarkedTruncated() throws Exception {
        List<VariableBinding> many = varbinds();
        for (int i = 0; i < 100; i++) {
            many.add(new VariableBinding(new OID("1.3.6.1.4.1.1." + i), new OctetString("x".repeat(500))));
        }

        TrapState.Snapshot snapshot = new TrapState().record(notification(many, false));

        assertTrue(snapshot.varbindsJson().length() <= TrapState.MAX_VARBINDS_JSON_CHARS);
        assertTrue(snapshot.varbindsJson().endsWith(",{\"truncated\":true}]"));
        assertTrue(snapshot.json().contains("{\"truncated\":true}]}"));
    }

    @Test
    void upstreamTruncationIsMarkedEvenWhenItFits() throws Exception {
        TrapState.Snapshot snapshot = new TrapState().record(notification(varbinds(), true));

        assertTrue(snapshot.varbindsJson().endsWith(",{\"truncated\":true}]"));
        assertFalse(new TrapState().record(notification(varbinds(), false)).varbindsJson().contains("truncated"));
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
