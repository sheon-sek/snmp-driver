package com.jw.snmpdriver.gateway.device;

import java.time.Instant;
import java.util.List;

import com.jw.snmpdriver.snmp.trap.TrapNotification;
import org.snmp4j.smi.VariableBinding;

/**
 * The most recent accepted Notification of one Device as an immutable {@link Snapshot}. Every Traps/Last node is
 * published from the same snapshot, so a reader of {@code Last/Json} never sees a mix of two Notifications.
 */
final class TrapState {
    /** Upper bound of the varbinds JSON (16 KiB; the text is ASCII-escaped enough that chars track bytes). */
    static final int MAX_VARBINDS_JSON_CHARS = 16 * 1024;

    private static final String TRUNCATED_ENTRY = "{\"truncated\":true}";

    record Snapshot(
            long sequence,
            Instant receivedAt,
            String pduType,
            String version,
            String trapOid,
            String source,
            String agentAddress,
            long agentUpTime,
            String varbindsJson,
            String json
    ) {
    }

    private volatile Snapshot last = new Snapshot(0, null, "", "", "", "", "", 0, "", "");

    Snapshot last() {
        return last;
    }

    /** Called by the single listener worker; replaces the snapshot and returns it. */
    synchronized Snapshot record(TrapNotification notification) {
        long sequence = last.sequence() + 1;
        String source = notification.source().getHostAddress() + ":" + notification.sourcePort();
        String agentAddress = notification.agentAddress() == null ? "" : notification.agentAddress();
        String varbinds = varbindsJson(notification.varbinds(), notification.varbindsTruncated());
        String json = "{\"sequence\":" + sequence
                + ",\"receivedTime\":" + quote(notification.receivedAt().toString())
                + ",\"pduType\":" + quote(notification.pduType().name())
                + ",\"version\":" + quote(notification.version().name())
                + ",\"trapOid\":" + quote(notification.trapOid())
                + ",\"source\":" + quote(source)
                + ",\"agentAddress\":" + quote(agentAddress)
                + ",\"agentUpTime\":" + notification.agentUpTime()
                + ",\"varbinds\":" + varbinds + "}";
        Snapshot snapshot = new Snapshot(sequence, notification.receivedAt(), notification.pduType().name(),
                notification.version().name(), notification.trapOid(), source, agentAddress,
                notification.agentUpTime(), varbinds, json);
        last = snapshot;
        return snapshot;
    }

    /** Notification Record (ADR 0010): the snapshot's {@code Last/Json} with a leading {@code device} field. */
    static String recordJson(String device, Snapshot snapshot) {
        String json = snapshot.json();
        // the snapshot before any Notification has no JSON; never emit a dangling comma
        if (!json.startsWith("{") || json.equals("{}")) {
            return "{\"device\":" + quote(device) + "}";
        }
        return "{\"device\":" + quote(device) + "," + json.substring(1);
    }

    private static String varbindsJson(List<VariableBinding> varbinds, boolean alreadyTruncated) {
        StringBuilder text = new StringBuilder("[");
        boolean truncated = alreadyTruncated;
        boolean first = true;
        for (VariableBinding binding : varbinds) {
            String entry = "{\"oid\":" + quote(binding.getOid().toString())
                    + ",\"type\":" + quote(binding.getVariable().getSyntaxString())
                    + ",\"value\":" + quote(binding.getVariable().toString()) + "}";
            // keep room for the marker and the closing bracket
            if (text.length() + 1 + entry.length() + 1 + TRUNCATED_ENTRY.length() + 1 > MAX_VARBINDS_JSON_CHARS) {
                truncated = true;
                break;
            }
            if (!first) {
                text.append(',');
            }
            text.append(entry);
            first = false;
        }
        if (truncated) {
            if (!first) {
                text.append(',');
            }
            text.append(TRUNCATED_ENTRY);
        }
        return text.append(']').toString();
    }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20) {
                        text.append(String.format("\\u%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        return text.append('"').toString();
    }
}
