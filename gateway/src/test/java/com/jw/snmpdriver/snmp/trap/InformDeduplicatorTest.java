package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class InformDeduplicatorTest {
    private final long[] now = {0};
    private final InformDeduplicator dedup = new InformDeduplicator(() -> now[0]);

    private static InformDeduplicator.Key key(int requestId) throws Exception {
        return new InformDeduplicator.Key(InetAddress.getByName("10.0.0.1"), 40000,
                TrapNotification.Version.V2C, "public".getBytes(StandardCharsets.UTF_8), "", requestId);
    }

    @Test
    void recordedKeyIsFoundUntilItsTtlExpires() throws Exception {
        assertFalse(dedup.contains(key(1)));
        dedup.record(key(1));
        assertTrue(dedup.contains(key(1)));
        assertFalse(dedup.contains(key(2)));

        now[0] = TimeUnit.SECONDS.toNanos(59);
        assertTrue(dedup.contains(key(1)));
        now[0] = TimeUnit.SECONDS.toNanos(61);
        assertFalse(dedup.contains(key(1)));
    }

    @Test
    void keepsAtMost4096EntriesAndEvictsTheLeastRecentlyUsed() throws Exception {
        for (int i = 0; i < 4096; i++) {
            dedup.record(key(i));
        }
        assertTrue(dedup.contains(key(0))); // touching 0 makes 1 the eldest
        dedup.record(key(5000));

        assertTrue(dedup.contains(key(0)));
        assertFalse(dedup.contains(key(1)));
        assertTrue(dedup.contains(key(5000)));
        assertTrue(dedup.contains(key(4095)));
    }

    @Test
    void keyDiffersByCommunityAndSourcePortAndDoesNotKeepThePlaintextCommunity() throws Exception {
        InformDeduplicator.Key base = key(7);
        InformDeduplicator.Key otherCommunity = new InformDeduplicator.Key(base.source(), base.sourcePort(),
                base.version(), "other".getBytes(StandardCharsets.UTF_8), "", 7);
        InformDeduplicator.Key otherPort = new InformDeduplicator.Key(base.source(), 40001, base.version(),
                "public".getBytes(StandardCharsets.UTF_8), "", 7);
        assertNotEquals(base, otherCommunity);
        assertNotEquals(base, otherPort);
        assertFalse(base.toString().contains("public"));
    }
}
