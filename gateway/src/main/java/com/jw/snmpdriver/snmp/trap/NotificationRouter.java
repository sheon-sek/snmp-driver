package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

/**
 * Routes one Listener's Notifications to the Devices that own the source address. O(1) per packet,
 * lock-free, and never throws into the transport thread.
 */
final class NotificationRouter implements NotificationHandler {
    private static final Logger logger = LoggerFactory.getLogger(NotificationRouter.class);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int LOG_SOURCES_MAX = 1024;

    private final int port;
    private final List<TrapRecipient> recipients = new CopyOnWriteArrayList<>();
    private final AtomicLong unmatched = new AtomicLong();
    private final AtomicLong multiMatch = new AtomicLong();
    private final Map<InetAddress, UnmatchedLog> unmatchedLog = new ConcurrentHashMap<>();

    NotificationRouter(int port) {
        this.port = port;
    }

    void add(TrapRecipient recipient) {
        recipients.add(recipient);
    }

    void remove(TrapRecipient recipient) {
        recipients.remove(recipient);
    }

    long unmatchedCount() {
        return unmatched.get();
    }

    long multiMatchCount() {
        return multiMatch.get();
    }

    @Override
    public void onNotification(IncomingNotification incoming) {
        try {
            route(incoming);
        } catch (RuntimeException error) {
            logger.warn("Failed to route Notification port={} source={} category=trap",
                    port, incoming.source(), error);
        }
    }

    private void route(IncomingNotification incoming) {
        List<TrapRecipient> matched = null;
        for (TrapRecipient recipient : recipients) {
            if (recipient.filter().matchesSource(incoming.source())) {
                if (matched == null) {
                    matched = new java.util.ArrayList<>(2);
                }
                matched.add(recipient);
            }
        }
        if (matched == null) {
            unmatched.incrementAndGet();
            logUnmatched(incoming.source());
            return;
        }
        if (matched.size() > 1) {
            multiMatch.incrementAndGet();
        }
        TrapNotification notification = null;
        boolean parsed = false;
        for (TrapRecipient recipient : matched) {
            if (!recipient.filter().authenticate(incoming.community())) {
                recipient.counters().countAuthFailure();
                recipient.notice().accept("来自 " + incoming.source().getHostAddress()
                        + " 的 Notification 认证失败：community 不匹配。请检查 Trap Community"
                        + "（为空时使用 Read Community）。");
                continue;
            }
            if (!parsed) {
                notification = parse(incoming);
                parsed = true;
            }
            if (notification == null) {
                logger.debug("Ignored malformed or unsupported Notification port={} source={} category=trap",
                        port, incoming.source());
                return;
            }
            recipient.counters().countReceived();
            recipient.sink().accept(notification);
        }
    }

    static TrapNotification parse(IncomingNotification incoming) {
        PDU pdu = incoming.pdu();
        Instant now = Instant.now();
        switch (pdu.getType()) {
            case PDU.V1TRAP -> {
                if (!(pdu instanceof PDUv1 v1)) {
                    return null;
                }
                List<VariableBinding> varbinds = V1TrapNormalizer.normalize(v1);
                return new TrapNotification(now, TrapNotification.Version.V1, TrapNotification.PduType.V1_TRAP,
                        incoming.source(), incoming.sourcePort(), v1.getAgentAddress().toString(),
                        v1.getTimestamp(), V1TrapNormalizer.trapOid(v1).toDottedString(), varbinds);
            }
            case PDU.TRAP, PDU.INFORM -> {
                List<VariableBinding> varbinds = List.copyOf(pdu.getAll());
                if (varbinds.size() < 2
                        || !V1TrapNormalizer.SYS_UP_TIME.equals(varbinds.get(0).getOid())
                        || !V1TrapNormalizer.SNMP_TRAP_OID.equals(varbinds.get(1).getOid())
                        || !(varbinds.get(0).getVariable() instanceof TimeTicks upTime)
                        || !(varbinds.get(1).getVariable() instanceof OID trapOid)) {
                    return null;
                }
                TrapNotification.PduType type = pdu.getType() == PDU.INFORM
                        ? TrapNotification.PduType.INFORM : TrapNotification.PduType.V2_TRAP;
                return new TrapNotification(now, incoming.version(), type, incoming.source(),
                        incoming.sourcePort(), null, upTime.getValue(), trapOid.toDottedString(), varbinds);
            }
            default -> {
                return null;
            }
        }
    }

    private void logUnmatched(InetAddress source) {
        long now = System.nanoTime();
        if (unmatchedLog.size() >= LOG_SOURCES_MAX && !unmatchedLog.containsKey(source)) {
            unmatchedLog.clear(); // bounded: spoofed sources cannot grow it
        }
        UnmatchedLog entry = unmatchedLog.computeIfAbsent(source, key -> new UnmatchedLog(now - LOG_INTERVAL_NANOS));
        synchronized (entry) {
            entry.suppressed++;
            if (now - entry.lastLoggedNanos >= LOG_INTERVAL_NANOS) {
                logger.info("Unmatched Notification port={} source={} count={} category=trap",
                        port, source.getHostAddress(), entry.suppressed);
                entry.suppressed = 0;
                entry.lastLoggedNanos = now;
            }
        }
    }

    private static final class UnmatchedLog {
        long lastLoggedNanos;
        long suppressed;

        UnmatchedLog(long lastLoggedNanos) {
            this.lastLoggedNanos = lastLoggedNanos;
        }
    }
}
