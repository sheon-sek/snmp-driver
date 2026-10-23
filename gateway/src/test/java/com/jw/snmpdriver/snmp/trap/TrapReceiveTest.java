package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** Real loopback datagrams through the real SNMP4J binder, registry, filter and router. */
class TrapReceiveTest {
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);

    {
        scheduler.setRemoveOnCancelPolicy(true);
    }
    private TrapListenerRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.closeAll();
        }
        scheduler.shutdownNow();
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static TrapRecipient recipient(BlockingQueue<TrapNotification> sink, TrapCounters counters) {
        return new TrapRecipient("dev", new TrapFilter("127.0.0.1", "", "public".getBytes(StandardCharsets.UTF_8),
                InetAddress::getAllByName), counters, new TokenBucket(1000), sink::add, notice -> { }, true);
    }

    private static void send(int port, int version, String community, PDU pdu) throws Exception {
        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            CommunityTarget<org.snmp4j.smi.Address> target = new CommunityTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setCommunity(new OctetString(community));
            target.setVersion(version);
            snmp.send(pdu, target);
        }
    }

    @Test
    void v2cTrapOverUdpReachesTheRecipient() throws Exception {
        int port = freePort();
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);
        BlockingQueue<TrapNotification> sink = new LinkedBlockingQueue<>();
        TrapCounters counters = new TrapCounters();
        registry.acquire("dev", "127.0.0.1", port, null, recipient(sink, counters));

        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(900)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        send(port, SnmpConstants.version2c, "public", pdu);

        TrapNotification n = sink.poll(5, TimeUnit.SECONDS);
        assertNotNull(n);
        assertEquals(TrapNotification.Version.V2C, n.version());
        assertEquals("1.3.6.1.6.3.1.1.5.3", n.trapOid());
        assertEquals("127.0.0.1", n.source().getHostAddress());
        assertEquals(1, counters.received());
    }

    @Test
    void v1TrapOverUdpIsConvertedAndAuthenticated() throws Exception {
        int port = freePort();
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);
        BlockingQueue<TrapNotification> sink = new LinkedBlockingQueue<>();
        registry.acquire("dev", "127.0.0.1", port, null, recipient(sink, new TrapCounters()));

        PDUv1 pdu = new PDUv1();
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9999"));
        pdu.setAgentAddress(new IpAddress("10.1.2.3"));
        pdu.setGenericTrap(6);
        pdu.setSpecificTrap(9);
        pdu.setTimestamp(123);
        send(port, SnmpConstants.version1, "public", pdu);

        TrapNotification n = sink.poll(5, TimeUnit.SECONDS);
        assertNotNull(n);
        assertEquals(TrapNotification.Version.V1, n.version());
        assertEquals("1.3.6.1.4.1.9999.0.9", n.trapOid());
        assertEquals("10.1.2.3", n.agentAddress());
    }

    @Test
    void wrongCommunityOverUdpOnlyCountsAuthFailure() throws Exception {
        int port = freePort();
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);
        BlockingQueue<TrapNotification> sink = new LinkedBlockingQueue<>();
        TrapCounters counters = new TrapCounters();
        registry.acquire("dev", "127.0.0.1", port, null, recipient(sink, counters));

        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(1)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        send(port, SnmpConstants.version2c, "nope", pdu);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counters.authFailure() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, counters.authFailure());
        assertEquals(0, counters.received());
        assertTrue(sink.isEmpty());
    }

    @Test
    void releaseAndCloseAllCancelTheSourceRefreshTasks() throws Exception {
        int portA = freePort();
        int portB = freePort();
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);
        BlockingQueue<TrapNotification> sink = new LinkedBlockingQueue<>();

        TrapListenerHandle a = registry.acquire("a", "127.0.0.1", portA, null, recipient(sink, new TrapCounters()));
        registry.acquire("b", "127.0.0.1", portB, null, recipient(sink, new TrapCounters()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scheduler.getQueue().size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(2, scheduler.getQueue().size());

        a.release();
        assertEquals(1, scheduler.getQueue().size());

        registry.closeAll();
        assertEquals(0, scheduler.getQueue().size());
    }

    @Test
    void transportThreadReadingHandleStateCannotDeadlockClose() throws Exception {
        AtomicReference<TrapListenerHandle> handle = new AtomicReference<>();
        AtomicBoolean joined = new AtomicBoolean();
        // stands in for SNMP4J close(), which waits for the listener thread to finish
        TrapListenerRegistry local = new TrapListenerRegistry((address, port) -> () -> {
            Thread transport = new Thread(() -> handle.get().state());
            transport.start();
            try {
                transport.join(3000);
                joined.set(!transport.isAlive());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, scheduler);
        handle.set(local.acquire("a", "127.0.0.1", freePort()));

        handle.get().release();

        assertTrue(joined.get(), "the transport thread must be able to read state while close() holds the lock");
    }

    @Test
    void transportThreadReadingListenerCountersCannotDeadlockClose() throws Exception {
        AtomicReference<TrapListenerHandle> handle = new AtomicReference<>();
        AtomicBoolean joined = new AtomicBoolean();
        // the transport thread's notice waits for the diagnostics lock, whose holder (flushCounters) reads these
        TrapListenerRegistry local = new TrapListenerRegistry((address, port) -> () -> {
            Thread transport = new Thread(() -> {
                handle.get().unmatchedCount();
                handle.get().multiMatchCount();
            });
            transport.start();
            try {
                transport.join(3000);
                joined.set(!transport.isAlive());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, scheduler);
        handle.set(local.acquire("a", "127.0.0.1", freePort()));

        handle.get().release();

        assertTrue(joined.get(), "listener counters must be readable while close() holds the registry lock");
    }
}
