package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TrapOidStatesTest {
    private static final Instant T1 = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant T2 = Instant.parse("2026-01-02T03:04:06Z");

    private static String oid(int n) {
        return "1.3.6.1.4.1.99." + n;
    }

    private static void arrive(TrapOidStates states, int n) {
        states.record(oid(n), n, T1, "[]");
    }

    @Test
    void anUntrackedOidReadsAsEmptyWithCountZero() {
        TrapOidStates.Entry entry = new TrapOidStates().get(oid(1));

        assertEquals(0L, entry.count());
        assertEquals(0L, entry.sequence());
        assertNull(entry.lastTime());
        assertEquals("", entry.lastVarbindsJson());
    }

    @Test
    void recordKeepsCountLastTimeSequenceAndVarbindsPerOid() {
        TrapOidStates states = new TrapOidStates();
        states.record(oid(1), 1, T1, "[a]");
        states.record(oid(2), 2, T1, "[b]");
        TrapOidStates.Entry second = states.record(oid(1), 3, T2, "[c]");

        assertEquals(2L, second.count());
        assertEquals(3L, second.sequence());
        assertEquals(T2, second.lastTime());
        assertEquals("[c]", second.lastVarbindsJson());
        assertEquals(second, states.get(oid(1)));
        assertEquals(1L, states.get(oid(2)).count());
        assertEquals(0L, states.overflowCount());
    }

    @Test
    void arrivalsTrackUpToTheArrivalLimitThenOverflowWithoutEvicting() {
        TrapOidStates states = new TrapOidStates();
        int limit = TrapOidStates.MAX_TRACKED - TrapOidStates.RESERVED_FOR_SUBSCRIBED;
        for (int n = 1; n <= limit; n++) {
            arrive(states, n);
        }
        assertEquals(limit, states.tracked());

        assertNull(states.record(oid(1000), 1000, T2, "[]"));
        assertNull(states.record(oid(1000), 1001, T2, "[]"));
        assertEquals(2L, states.overflowCount());
        assertEquals(0L, states.get(oid(1000)).count());
        assertEquals(limit, states.tracked());

        // already tracked OIDs are never evicted and keep counting
        assertEquals(2L, states.record(oid(1), 1002, T2, "[]").count());
        assertEquals(2L, states.overflowCount());
    }

    @Test
    void subscribedOidsMayUseTheReservedSlotsUpToTheHardLimit() {
        TrapOidStates states = new TrapOidStates();
        for (int n = 1; n <= TrapOidStates.MAX_TRACKED - TrapOidStates.RESERVED_FOR_SUBSCRIBED; n++) {
            arrive(states, n);
        }
        for (int n = 0; n < TrapOidStates.RESERVED_FOR_SUBSCRIBED; n++) {
            assertTrue(states.reserve(oid(2000 + n)), "reserved slot " + n);
        }
        assertEquals(TrapOidStates.MAX_TRACKED, states.tracked());
        assertFalse(states.reserve(oid(3000)));

        // a subscribed OID tracks its first arrival even though arrivals are capped; the rest is overflow
        assertEquals(1L, states.record(oid(2000), 1, T1, "[]").count());
        assertNull(states.record(oid(3000), 2, T1, "[]"));
        assertNull(states.record(oid(4000), 3, T1, "[]"));
        assertEquals(2L, states.overflowCount());
        assertEquals(TrapOidStates.MAX_TRACKED, states.tracked());
    }

    @Test
    void reservingATrackedOidTakesNoNewSlot() {
        TrapOidStates states = new TrapOidStates();
        arrive(states, 1);

        assertTrue(states.reserve(oid(1)));
        assertTrue(states.reserve(oid(1)));
        assertEquals(1, states.tracked());
        assertEquals(1L, states.get(oid(1)).count());
    }
}
