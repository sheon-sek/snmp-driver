package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** Real loopback v2c INFORMs through the SNMP4J binder and router: a RESPONSE arrives only when ADR 0004 says so. */
class InformAckTest {
    private volatile LicenseGate.Verdict verdict = LicenseGate.Verdict.LICENSED;
    private final TrapCounters counters = new TrapCounters();
    private NotificationRouter router;
    private Closeable listener;
    private Snmp sender;
    private int port;

    @AfterEach
    void tearDown() throws Exception {
        if (sender != null) {
            sender.close();
        }
        if (listener != null) {
            listener.close();
        }
        if (router != null) {
            router.close();
        }
    }

    private void listen(int queueCapacity, java.util.function.Consumer<TrapNotification> sink) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        router = new NotificationRouter(port, () -> verdict, queueCapacity);
        router.add(new TrapRecipient("dev", new TrapFilter("127.0.0.1", "",
                "public".getBytes(StandardCharsets.UTF_8), InetAddress::getAllByName), counters, new TokenBucket(1000),
                sink, notice -> { }, true));
        router.start();
        listener = new Snmp4jListenerBinder().bind(InetAddress.getLoopbackAddress(), port, router);
        sender = new Snmp(new DefaultUdpTransportMapping());
        sender.listen();
    }

    private ResponseEvent<?> sendInform(int requestId) throws Exception {
        PDU pdu = new PDU();
        pdu.setType(PDU.INFORM);
        pdu.setRequestID(new Integer32(requestId));
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(900)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"), new OctetString("probe")));
        CommunityTarget<org.snmp4j.smi.Address> target = new CommunityTarget<>();
        target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
        target.setCommunity(new OctetString("public"));
        target.setVersion(SnmpConstants.version2c);
        target.setTimeout(600);
        target.setRetries(0);
        return sender.send(pdu, target);
    }

    private void awaitAccepted(long count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counters.accepted() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void acceptedInformGetsAResponseEchoingItsRequestAndVarbinds() throws Exception {
        listen(100, n -> { });

        PDU response = sendInform(4242).getResponse();

        assertNotNull(response, "an accepted Inform must be ACKed");
        assertEquals(PDU.RESPONSE, response.getType());
        assertEquals(4242, response.getRequestID().getValue());
        assertEquals(0, response.getErrorStatus());
        assertEquals(0, response.getErrorIndex());
        assertEquals(3, response.size());
        assertEquals("probe", response.get(2).getVariable().toString());
    }

    @Test
    void noLicenseMeansNoResponse() throws Exception {
        listen(100, n -> { });
        verdict = LicenseGate.Verdict.EXPIRED;

        assertNull(sendInform(1).getResponse());
        assertEquals(1, counters.droppedUnlicensed());
    }

    @Test
    void fullQueueMeansNoResponse() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        listen(1, n -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        // the first Inform is ACKed and blocks the worker, the second fills the one-slot queue
        assertNotNull(sendInform(1).getResponse());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertNotNull(sendInform(2).getResponse());

        assertNull(sendInform(3).getResponse());
        assertEquals(1, counters.droppedQueueFull());
        release.countDown();
    }

    @Test
    void retransmittedInformIsAckedAgainButAcceptedOnlyOnce() throws Exception {
        listen(100, n -> { });

        assertNotNull(sendInform(77).getResponse());
        awaitAccepted(1);
        assertNotNull(sendInform(77).getResponse(), "a duplicate is ACKed again");

        Thread.sleep(100);
        assertEquals(1, counters.accepted());
        assertEquals(1, counters.duplicateInform());
    }

    @Test
    void resendAfterAFailedAttemptIsNotTreatedAsDuplicate() throws Exception {
        listen(100, n -> { });
        verdict = LicenseGate.Verdict.EXPIRED;
        assertNull(sendInform(5).getResponse());

        verdict = LicenseGate.Verdict.LICENSED;
        assertNotNull(sendInform(5).getResponse());
        awaitAccepted(1);

        assertEquals(1, counters.accepted());
        assertEquals(0, counters.duplicateInform());
    }
}
