package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

/** ADR 0004: authenticate, route, License, dedup lookup, rate limit, offer, dedup write, then ACK. */
class NotificationRouterInformTest {
    private final long[] clock = {0};
    private volatile LicenseGate.Verdict verdict = LicenseGate.Verdict.LICENSED;
    private NotificationRouter router = new NotificationRouter(10162, () -> verdict, 100, () -> clock[0]);
    private final AtomicInteger acks = new AtomicInteger();

    @AfterEach
    void stop() {
        router.close();
    }

    private static final class Device {
        final TrapCounters counters = new TrapCounters();
        final List<TrapNotification> received = new CopyOnWriteArrayList<>();
        final TrapRecipient recipient;

        Device(String host, TokenBucket limiter, boolean acceptInform) {
            recipient = new TrapRecipient(host, new TrapFilter(host, "", "public".getBytes(StandardCharsets.UTF_8),
                    h -> { throw new java.net.UnknownHostException(h); }), counters, limiter, received::add,
                    notice -> { }, acceptInform);
        }

        Device(String host) {
            this(host, new TokenBucket(1000), true);
        }

        void awaitAccepted(long count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (counters.accepted() < count && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        }
    }

    private IncomingNotification inform(String source, int requestId) throws Exception {
        return inform(source, 40000, "public", requestId);
    }

    private IncomingNotification inform(String source, int port, String community, int requestId) throws Exception {
        PDU pdu = new PDU();
        pdu.setType(PDU.INFORM);
        pdu.setRequestID(new Integer32(requestId));
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.3.0"), new TimeTicks(500)));
        pdu.add(new VariableBinding(new OID("1.3.6.1.6.3.1.1.4.1.0"), new OID("1.3.6.1.6.3.1.1.5.4")));
        return new IncomingNotification(InetAddress.getByName(source), port, TrapNotification.Version.V2C,
                community.getBytes(StandardCharsets.UTF_8), pdu, acks::incrementAndGet);
    }

    private IncomingNotification plainTrap(String source) throws Exception {
        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.3.0"), new TimeTicks(500)));
        pdu.add(new VariableBinding(new OID("1.3.6.1.6.3.1.1.4.1.0"), new OID("1.3.6.1.6.3.1.1.5.3")));
        return new IncomingNotification(InetAddress.getByName(source), 40000, TrapNotification.Version.V2C,
                "public".getBytes(StandardCharsets.UTF_8), pdu, null);
    }

    private static void drain(TokenBucket bucket) {
        while (bucket.tryAcquire()) {
            // burst is twice the rate
        }
    }

    private Device startWith(Device... devices) {
        for (Device device : devices) {
            router.add(device.recipient);
        }
        router.start();
        return devices[0];
    }

    private static IncomingNotification v3Inform(byte[] contextName) throws Exception {
        org.snmp4j.ScopedPDU pdu = new org.snmp4j.ScopedPDU();
        pdu.setType(PDU.INFORM);
        pdu.setRequestID(new Integer32(7));
        pdu.setContextName(new org.snmp4j.smi.OctetString(contextName));
        return new IncomingNotification(InetAddress.getByName("10.0.0.1"), 40000, TrapNotification.Version.V3,
                "informuser".getBytes(StandardCharsets.UTF_8), pdu, null, new byte[] {(byte) 0x80, 0, 0x1F, 1, 2},
                org.snmp4j.security.SecurityLevel.AUTH_PRIV);
    }

    @Test
    void binaryContextNameNeverCollidesWithPrintableTextInTheDedupKey() throws Exception {
        byte[] binary = {0x01, 0x02};
        byte[] text = "01:02".getBytes(StandardCharsets.US_ASCII);
        assertEquals(NotificationRouter.informKey(v3Inform(binary)), NotificationRouter.informKey(v3Inform(binary.clone())));
        assertNotEquals(NotificationRouter.informKey(v3Inform(binary)), NotificationRouter.informKey(v3Inform(text)));
        assertNotEquals(NotificationRouter.informKey(v3Inform(new byte[0])),
                NotificationRouter.informKey(v3Inform(new byte[] {0})));
    }

    @Test
    void informIsAckedOnceAfterItEntersTheQueue() throws Exception {
        Device a = startWith(new Device("10.0.0.1"));

        router.onNotification(inform("10.0.0.1", 1));

        assertEquals(1, acks.get());
        a.awaitAccepted(1);
        assertEquals(1, a.counters.accepted());
        assertEquals(1, a.counters.received());
        assertEquals(0, a.counters.duplicateInform());
    }

    @Test
    void withoutLicenseNothingIsAckedAndResendAfterLicensingIsNotADuplicate() throws Exception {
        Device a = startWith(new Device("10.0.0.1"));
        verdict = LicenseGate.Verdict.EXPIRED;

        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(0, acks.get());
        assertEquals(1, a.counters.droppedUnlicensed());

        verdict = LicenseGate.Verdict.LICENSED;
        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(1, acks.get());
        a.awaitAccepted(1);
        assertEquals(1, a.counters.accepted());
        assertEquals(0, a.counters.duplicateInform());
    }

    @Test
    void rateLimitedInformIsNotAckedAndItsResendIsNotADuplicate() throws Exception {
        TokenBucket bucket = new TokenBucket(1, () -> clock[0]);
        Device a = startWith(new Device("10.0.0.1", bucket, true));
        drain(bucket);

        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(0, acks.get());
        assertEquals(1, a.counters.droppedRateLimit());

        clock[0] = TimeUnit.SECONDS.toNanos(2);
        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(1, acks.get());
        assertEquals(0, a.counters.duplicateInform());
    }

    @Test
    void fullQueueInformIsNotAckedAndItsResendIsNotADuplicate() throws Exception {
        router.close();
        router = new NotificationRouter(10162, () -> verdict, 1, () -> clock[0]);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TrapCounters counters = new TrapCounters();
        router.add(new TrapRecipient("slow", new TrapFilter("10.0.0.1", "", "public".getBytes(StandardCharsets.UTF_8),
                h -> { throw new java.net.UnknownHostException(h); }), counters, new TokenBucket(1000), n -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }, notice -> { }, true));
        router.start();
        router.onNotification(plainTrap("10.0.0.1"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        router.onNotification(plainTrap("10.0.0.1")); // fills the one-slot queue

        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(0, acks.get());
        assertEquals(1, counters.droppedQueueFull());

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (router.queued() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(1, acks.get());
        assertEquals(0, counters.duplicateInform());
    }

    @Test
    void duplicateInformIsAckedAgainWithoutSpendingATokenOrQueueingAgain() throws Exception {
        TokenBucket bucket = new TokenBucket(1, () -> clock[0]);
        Device a = startWith(new Device("10.0.0.1", bucket, true));
        assertTrue(bucket.tryAcquire()); // one token left: a duplicate that charged it would be rate limited
        router.onNotification(inform("10.0.0.1", 9));
        a.awaitAccepted(1);

        router.onNotification(inform("10.0.0.1", 9));

        assertEquals(2, acks.get());
        assertEquals(1, a.counters.duplicateInform());
        assertEquals(2, a.counters.received());
        assertEquals(0, a.counters.droppedRateLimit());
        Thread.sleep(50);
        assertEquals(1, a.counters.accepted());
        assertEquals(1, a.received.size());
    }

    @Test
    void otherRequestIdCommunityOrPortIsNotADuplicateAndTtlExpiryAllowsRedelivery() throws Exception {
        Device a = startWith(new Device("10.0.0.1"));
        router.onNotification(inform("10.0.0.1", 1));
        router.onNotification(inform("10.0.0.1", 2));
        router.onNotification(inform("10.0.0.1", 40001, "public", 1));
        a.awaitAccepted(3);
        assertEquals(0, a.counters.duplicateInform());

        clock[0] = TimeUnit.SECONDS.toNanos(61);
        router.onNotification(inform("10.0.0.1", 1));
        a.awaitAccepted(4);
        assertEquals(4, a.counters.accepted());
        assertEquals(0, a.counters.duplicateInform());
    }

    @Test
    void oneFailingDeviceDoesNotBlockTheAckButAllFailingDoNot() throws Exception {
        Device good = new Device("10.0.0.1");
        TokenBucket bucket = new TokenBucket(1, () -> clock[0]);
        Device limited = new Device("10.0.0.1", bucket, true);
        startWith(good, limited);
        drain(bucket);

        router.onNotification(inform("10.0.0.1", 1));
        assertEquals(1, acks.get());
        assertEquals(1, limited.counters.droppedRateLimit());
        good.awaitAccepted(1);
        assertEquals(1, good.counters.accepted());

        verdict = LicenseGate.Verdict.EXPIRED;
        router.onNotification(inform("10.0.0.1", 2));
        assertEquals(1, acks.get());
    }

    @Test
    void deduplicationIsPerDevice() throws Exception {
        Device a = new Device("10.0.0.1");
        Device b = new Device("10.0.0.1");
        startWith(a, b);
        router.onNotification(inform("10.0.0.1", 5));
        a.awaitAccepted(1);
        b.awaitAccepted(1);

        router.onNotification(inform("10.0.0.1", 5));

        assertEquals(2, acks.get());
        assertEquals(1, a.counters.duplicateInform());
        assertEquals(1, b.counters.duplicateInform());
        assertEquals(1, a.counters.accepted());
        assertEquals(1, b.counters.accepted());
    }

    @Test
    void deviceThatRefusesInformsOnlyCountsReceivedAndNeverAcks() throws Exception {
        Device a = startWith(new Device("10.0.0.1", new TokenBucket(1000), false));

        router.onNotification(inform("10.0.0.1", 1));
        router.onNotification(plainTrap("10.0.0.1"));
        a.awaitAccepted(1);

        assertEquals(0, acks.get());
        assertEquals(2, a.counters.received());
        assertEquals(1, a.counters.accepted()); // only the plain Trap
        assertEquals(0, a.counters.duplicateInform());
        assertEquals(0, a.counters.droppedRateLimit());
        assertEquals(0, a.counters.droppedUnlicensed());
    }

    @Test
    void wrongCommunityInformIsNotAcked() throws Exception {
        Device a = startWith(new Device("10.0.0.1"));

        router.onNotification(inform("10.0.0.1", 40000, "nope", 1));

        assertEquals(0, acks.get());
        assertEquals(1, a.counters.authFailure());
    }
}
