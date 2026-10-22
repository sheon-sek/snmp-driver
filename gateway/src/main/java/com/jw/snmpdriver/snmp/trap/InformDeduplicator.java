package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Remembers recently enqueued Informs of one Device so a retransmission is ACKed again instead of
 * delivered twice. Entries expire after {@link #TTL_NANOS} and at most {@link #CAPACITY} are kept (LRU).
 */
final class InformDeduplicator {
    static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final int CAPACITY = 4096;

    /** The community is kept only as a digest; {@code contextName} is empty for v1/v2c. */
    record Key(InetAddress source, int sourcePort, TrapNotification.Version version, String communityDigest,
               String contextName, int requestId) {
        Key(InetAddress source, int sourcePort, TrapNotification.Version version, byte[] community,
            String contextName, int requestId) {
            this(source, sourcePort, version, digest(community), contextName, requestId);
        }

        private static String digest(byte[] community) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(community));
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private final LongSupplier nanos;
    private final Map<Key, Long> expiresAt = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Long> eldest) {
            return size() > CAPACITY;
        }
    };

    InformDeduplicator(LongSupplier nanos) {
        this.nanos = nanos;
    }

    synchronized boolean contains(Key key) {
        Long expiry = expiresAt.get(key);
        if (expiry == null) {
            return false;
        }
        if (nanos.getAsLong() - expiry >= 0) {
            expiresAt.remove(key);
            return false;
        }
        return true;
    }

    synchronized void record(Key key) {
        expiresAt.put(key, nanos.getAsLong() + TTL_NANOS);
    }
}
