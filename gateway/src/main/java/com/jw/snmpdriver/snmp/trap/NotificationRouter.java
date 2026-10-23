package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.jw.snmpdriver.snmp.runtime.LicenseGate;
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
final class NotificationRouter implements NotificationHandler, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NotificationRouter.class);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int LOG_SOURCES_MAX = 1024;

    static final int QUEUE_CAPACITY = 10_000;

    private final int port;
    private final Supplier<LicenseGate.Verdict> license;
    private final BlockingQueue<Delivery> queue;
    private final Thread worker;
    private volatile boolean closed;
    private final List<TrapRecipient> recipients = new CopyOnWriteArrayList<>();
    private final AtomicLong unmatched = new AtomicLong();
    private final AtomicLong multiMatch = new AtomicLong();
    private final Map<InetAddress, UnmatchedLog> unmatchedLog = new ConcurrentHashMap<>();
    private final Map<TrapRecipient, InformDeduplicator> deduplicators = new ConcurrentHashMap<>();
    private final LongSupplier nanos;

    NotificationRouter(int port, Supplier<LicenseGate.Verdict> license) {
        this(port, license, QUEUE_CAPACITY);
    }

    NotificationRouter(int port, Supplier<LicenseGate.Verdict> license, int queueCapacity) {
        this(port, license, queueCapacity, System::nanoTime);
    }

    NotificationRouter(int port, Supplier<LicenseGate.Verdict> license, int queueCapacity, LongSupplier nanos) {
        this.nanos = nanos;
        this.port = port;
        this.license = license;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::drain, "snmp-trap-" + port);
        this.worker.setDaemon(true);
    }

    /** One worker per Listener: Devices are served in arrival order and never on the transport thread. */
    void start() {
        worker.start();
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
        queue.clear();
    }

    private void drain() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Delivery delivery = queue.take();
                // a Device removed or reconfigured since the offer is a different recipient: drop silently
                if (!recipients.contains(delivery.recipient())) {
                    continue;
                }
                try {
                    delivery.recipient().sink().accept(delivery.notification());
                    delivery.recipient().counters().countAccepted();
                } catch (RuntimeException error) {
                    logger.warn("Failed to deliver Notification device={} port={} category=trap",
                            delivery.recipient().deviceName(), port, error);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record Delivery(TrapRecipient recipient, TrapNotification notification) {
    }

    void add(TrapRecipient recipient) {
        recipients.add(recipient);
    }

    void remove(TrapRecipient recipient) {
        recipients.remove(recipient);
        deduplicators.remove(recipient);
    }

    void clear() {
        recipients.clear();
        deduplicators.clear();
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
        boolean acknowledge = false;
        for (TrapRecipient recipient : matched) {
            try {
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
                if (incoming.ack() == null) {
                    admit(recipient, notification);
                } else if (recipient.acceptInform()) {
                    acknowledge |= admitInform(recipient, incoming, notification);
                }
            } catch (RuntimeException error) {
                // one Device's failing callback must not stop the broadcast to the others
                logger.warn("Failed to deliver Notification device={} port={} source={} category=trap",
                        recipient.deviceName(), port, incoming.source(), error);
            }
        }
        if (acknowledge) {
            incoming.ack().run();
        }
    }

    /**
     * Inform: ACK once if any Device queued it or already had it. The dedup lookup precedes the rate limit
     * (a retransmission costs no token) and the write follows the offer, so an Inform that failed to queue is
     * never mistaken for a duplicate when the sender retries (ADR 0004).
     */
    private boolean admitInform(TrapRecipient recipient, IncomingNotification incoming, TrapNotification notification) {
        if (license.get() != LicenseGate.Verdict.LICENSED) {
            recipient.counters().countDroppedUnlicensed();
            return false;
        }
        InformDeduplicator deduplicator = deduplicators.computeIfAbsent(recipient, key -> new InformDeduplicator(nanos));
        InformDeduplicator.Key key = new InformDeduplicator.Key(incoming.source(), incoming.sourcePort(),
                incoming.version(), incoming.community(), "", incoming.pdu().getRequestID().getValue());
        if (deduplicator.contains(key)) {
            recipient.counters().countDuplicateInform();
            return true;
        }
        if (!enqueue(recipient, notification)) {
            return false;
        }
        deduplicator.record(key);
        return true;
    }

    private void admit(TrapRecipient recipient, TrapNotification notification) {
        if (license.get() != LicenseGate.Verdict.LICENSED) {
            recipient.counters().countDroppedUnlicensed();
            return;
        }
        enqueue(recipient, notification);
    }

    /** The Device's rate limit, then the shared queue; every refusal has its own counter. */
    private boolean enqueue(TrapRecipient recipient, TrapNotification notification) {
        if (!recipient.limiter().tryAcquire()) {
            recipient.counters().countDroppedRateLimit();
            return false;
        }
        if (closed) {
            return false;
        }
        if (!queue.offer(new Delivery(recipient, notification))) {
            recipient.counters().countDroppedQueueFull();
            return false;
        }
        if (closed) {
            // close() raced with the offer: nothing will drain it
            queue.clear();
        }
        return true;
    }

    int queued() {
        return queue.size();
    }

    static TrapNotification parse(IncomingNotification incoming) {
        PDU pdu = incoming.pdu();
        Instant now = Instant.now();
        switch (pdu.getType()) {
            case PDU.V1TRAP -> {
                if (!(pdu instanceof PDUv1 v1)) {
                    return null;
                }
                List<VariableBinding> all = V1TrapNormalizer.normalize(v1);
                boolean truncated = all.size() > TrapNotification.MAX_VARBINDS;
                List<VariableBinding> varbinds = truncated
                        ? List.copyOf(all.subList(0, TrapNotification.MAX_VARBINDS)) : all;
                return new TrapNotification(now, TrapNotification.Version.V1, TrapNotification.PduType.V1_TRAP,
                        incoming.source(), incoming.sourcePort(), v1.getAgentAddress().toString(),
                        v1.getTimestamp(), V1TrapNormalizer.trapOid(v1).toDottedString(), varbinds, truncated);
            }
            case PDU.TRAP, PDU.INFORM -> {
                List<VariableBinding> all = V1TrapNormalizer.withoutCommunity(List.copyOf(pdu.getAll()));
                boolean truncated = all.size() > TrapNotification.MAX_VARBINDS;
                List<VariableBinding> varbinds = truncated
                        ? List.copyOf(all.subList(0, TrapNotification.MAX_VARBINDS)) : all;
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
                        incoming.sourcePort(), null, upTime.getValue(), trapOid.toDottedString(), varbinds, truncated);
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
