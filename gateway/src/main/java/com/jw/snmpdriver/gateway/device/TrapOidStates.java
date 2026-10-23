package com.jw.snmpdriver.gateway.device;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-trapOID state behind the {@code TRAP.*} Item Paths. One writer (the listener worker) calls
 * {@link #record}; readers use {@link #get} without locking. A tracked OID is never evicted. Of the
 * {@link #MAX_TRACKED} slots, {@link #RESERVED_FOR_SUBSCRIBED} are kept for OIDs a {@code TRAP.*} path already
 * subscribed to, so a flood of random trapOIDs cannot lock out the paths an operator configured.
 * Values are independent counters and a latest entry, not one atomic snapshot (ADR 0008: use Traps/Last/Json).
 */
final class TrapOidStates {
    static final int MAX_TRACKED = 256;
    static final int RESERVED_FOR_SUBSCRIBED = 16;

    /** Immutable, so a reader sees one consistent entry for its OID. */
    record Entry(long count, Instant lastTime, long sequence, String lastVarbindsJson) {
        static final Entry EMPTY = new Entry(0, null, 0, "");
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile long overflow;

    /** The OID's state; Count=0 and empty fields when it is not tracked. */
    Entry get(String trapOid) {
        return entries.getOrDefault(trapOid, Entry.EMPTY);
    }

    long overflowCount() {
        return overflow;
    }

    int tracked() {
        return entries.size();
    }

    /**
     * Claims a slot for an OID a {@code TRAP.*} path subscribed to (it stays tracked after the path goes away);
     * false when all slots are taken by other OIDs.
     */
    synchronized boolean reserve(String trapOid) {
        if (!entries.containsKey(trapOid)) {
            if (entries.size() >= MAX_TRACKED) {
                return false;
            }
            entries.put(trapOid, Entry.EMPTY);
        }
        return true;
    }

    /** Called on the listener worker; returns the new entry, or null when the OID is not tracked (overflow). */
    synchronized Entry record(String trapOid, long sequence, Instant receivedAt, String varbindsJson) {
        Entry current = entries.get(trapOid);
        if (current == null) {
            if (entries.size() >= MAX_TRACKED - RESERVED_FOR_SUBSCRIBED) {
                overflow++;
                return null;
            }
            current = Entry.EMPTY;
        }
        Entry next = new Entry(current.count() + 1, receivedAt, sequence, varbindsJson);
        entries.put(trapOid, next);
        return next;
    }
}
