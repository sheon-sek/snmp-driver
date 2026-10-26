package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** v3 Traps from an in-process SNMP4J sender over real loopback UDP, through the real binder and Trap USM. */
public class TrapV3ReceiveTest {
    private static final String ENGINE_HEX = "80001f8880aabbccdd01";
    private static final byte[] ENGINE = HexFormat.of().parseHex(ENGINE_HEX);
    private static final String AUTH = "authpass123";
    private static final String PRIV = "privpass123";

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private TrapListenerRegistry registry;
    private final BlockingQueue<TrapNotification> sink = new LinkedBlockingQueue<>();
    private final TrapCounters counters = new TrapCounters();
    private final List<String> notices = new CopyOnWriteArrayList<>();

    {
        scheduler.setRemoveOnCancelPolicy(true);
    }

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

    private TrapRecipient recipient(SNMPDeviceConfig.SecurityLevel level, String senderEngineHex) {
        TrapV3User user = TrapV3User.of("trapuser", level, SNMPDeviceConfig.AuthProtocol.SHA256, AUTH,
                SNMPDeviceConfig.PrivacyProtocol.AES128, PRIV, senderEngineHex);
        return new TrapRecipient("dev", new TrapFilter("127.0.0.1", "", new byte[0], InetAddress::getAllByName),
                counters, new TokenBucket(1000), sink::add, notices::add, true, user);
    }

    private int listen(TrapRecipient recipient) throws Exception {
        int port = freePort();
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);
        registry.acquire("dev", "127.0.0.1", port, null, recipient);
        return port;
    }

    /** A v3 Trap whose authoritative engine is the sender's own engine, as RFC 3414 requires. */
    public static void sendV3(int port, byte[] engine, int boots, String user, int level, String auth, String priv)
            throws Exception {
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        USM usm = new USM(protocols, new OctetString(engine), boots);
        usm.addUser(new UsmUser(new OctetString(user),
                level >= SecurityLevel.AUTH_NOPRIV ? AuthHMAC192SHA256.ID : null,
                level >= SecurityLevel.AUTH_NOPRIV ? new OctetString(auth) : null,
                level == SecurityLevel.AUTH_PRIV ? PrivAES128.ID : null,
                level == SecurityLevel.AUTH_PRIV ? new OctetString(priv) : null), new OctetString(engine));
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(engine, null, protocols,
                new SecurityModels().addSecurityModel(usm), CounterSupport.getInstance()));
        try (Snmp snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping())) {
            snmp.listen();
            UserTarget<Address> target = new UserTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setVersion(SnmpConstants.version3);
            target.setSecurityLevel(level);
            target.setSecurityName(new OctetString(user));
            ScopedPDU pdu = new ScopedPDU();
            pdu.setType(PDU.TRAP);
            pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(4200)));
            pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.4")));
            snmp.send(pdu, target);
        }
    }

    private void assertReceivedV3() throws Exception {
        TrapNotification n = sink.poll(5, TimeUnit.SECONDS);
        assertNotNull(n, "authFailure=" + counters.authFailure() + " notices=" + notices);
        assertEquals(TrapNotification.Version.V3, n.version());
        assertEquals(TrapNotification.PduType.V2_TRAP, n.pduType());
        assertEquals("1.3.6.1.6.3.1.1.5.4", n.trapOid());
        assertEquals(4200, n.agentUpTime());
        assertEquals(1, counters.received());
        assertEquals(0, counters.authFailure());
    }

    private void assertRejected() throws Exception {
        assertNull(sink.poll(1, TimeUnit.SECONDS));
        assertEquals(0, counters.received());
    }

    @Test
    void authPrivTrapIsReceived() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        assertReceivedV3();
    }

    @Test
    void authNoPrivTrapIsReceived() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_NO_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.AUTH_NOPRIV, AUTH, null);
        assertReceivedV3();
    }

    @Test
    void noAuthNoPrivTrapIsReceived() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.NOAUTH_NOPRIV, null, null);
        assertReceivedV3();
    }

    @Test
    void wrongAuthPassphraseCountsAuthFailure() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.AUTH_PRIV, "wrongpass99", PRIV);
        assertRejected();
        assertEquals(1, counters.authFailure());
        assertTrue(notices.stream().anyMatch(text -> text.contains("认证失败")), notices.toString());
    }

    /** SNMP4J fires no AuthenticationFailureEvent for a decryption failure; it must still count (#41). */
    @Test
    void wrongPrivPassphraseCountsAuthFailure() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.AUTH_PRIV, AUTH, "wrongpriv99");
        assertRejected();
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertTrue(notices.stream().anyMatch(text -> text.contains("Privacy Passphrase")), notices.toString());
    }

    /**
     * The same failure as net-snmp 5.9 sends it (captured with {@code snmptrap -d -v 3 -u trapuser -l authPriv
     * -a SHA-256 -A authpass123 -x AES -X <wrong> -e 80001f8880aabbccdd01}), found by the M1d system test. The
     * decrypted garbage differs per packet, so several captures cover the different parse failures.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "3081af020103301102041b521d35020300ffe304010302010304443042040a80001f8880aabbccdd0102010102030fcace0408747261"
            + "707573657204185e861dadabfd8fdf6ec7c17a1c7bb78c39b942b85936772a0408339d4c584c4be13604518fa866f79179ec1898"
            + "6e209991b33708293b33bb60de616f670e273d42dea77b03bcfeb3f52234550aa145f67284f4a9255e5320f1b768064ab56fec56"
            + "58dc17f1740926556586978a7a27e2837f406326",
        "3081af020103301102040cd74778020300ffe304010302010304443042040a80001f8880aabbccdd0102010102030fe3160408747261"
            + "70757365720418c3abdccc162d69238718ea3a49affadf00410a06dc1eeae304080f16db715553034a045179dd45b199868f731c"
            + "947c5586089d0c4c5a842008ad47d6616651b7bdb68b527debb1efce6b3934239977389049627a960d83ddcb7a7dab221acb3b57"
            + "292abac27b3c2b6adf91045c462a281a90b8b93f",
        "3081af0201033011020424391138020300ffe304010302010304443042040a80001f8880aabbccdd0102010102030fe33a0408747261"
            + "70757365720418c5045a75be5899c166a02d4edd516fdfac5eaf782d38d97b0408c6316852922c41fa045142fe0166465db0dfdd"
            + "4792fdc7b69146a709bac476a3ae79b84b9567bec16ea356833890fec9fe308da6485cb85cb4089844cd402390e33f73b5ec34a0"
            + "c27f2b67f66de4ecf42e2e30f29b9f82fd011e85",
        "3081af02010330110204399deec9020300ffe304010302010304443042040a80001f8880aabbccdd0102010102030fe3580408747261"
            + "70757365720418d1f1f4db2237481f506175afe1175e63a0a52746429f96690408288b9b801b1901ad04519af7a914caae551879"
            + "856547d5c9ea597443204b1b6c8bcea604c2bbc9b8713566e02359d7153720328bd119643dc32cf1a4e172322570f9d87884a22f"
            + "98aebfeb2b9f90e5bc608419c791d6d02d112140"
    })
    void netSnmpTrapWithWrongPrivPassphraseCountsAuthFailure(String hex) throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        byte[] packet = HexFormat.of().parseHex(hex);
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.send(new java.net.DatagramPacket(packet, packet.length, InetAddress.getLoopbackAddress(), port));
        }
        assertRejected();
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertTrue(notices.stream().anyMatch(text -> text.contains("Privacy Passphrase")), notices.toString());
    }

    @Test
    void lowerSecurityLevelThanConfiguredCountsAuthFailure() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.AUTH_NOPRIV, AUTH, null);
        assertRejected();
        assertEquals(1, counters.authFailure());
    }

    @Test
    void unexpectedEngineIdCountsAuthFailureAndRegistersNoUser() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        TrapUsmStack stack = registry.usmStackOf(port);
        int users = stack.userCount();
        List<byte[]> spoofed = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            spoofed.add(HexFormat.of().parseHex("80001f88800000" + String.format("%06x", i)));
            sendV3(port, spoofed.get(i), 1, "trapuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        }
        assertRejected();
        assertTrue(waitFor(() -> counters.authFailure() == 20), "authFailure=" + counters.authFailure());
        assertEquals(users, stack.userCount());
        for (byte[] engine : spoofed) {
            assertTrue(!stack.tracksEngine(engine), "spoofed engine entered the time table");
        }
        assertTrue(notices.stream().anyMatch(text -> text.contains("engineID")), notices.toString());
    }

    @Test
    void engineBootsRollbackIsOutsideTheTimeWindow() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        sendV3(port, ENGINE, 5, "trapuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        assertReceivedV3();
        sendV3(port, ENGINE, 4, "trapuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        assertNull(sink.poll(1, TimeUnit.SECONDS));
        assertEquals(1, counters.received());
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertTrue(notices.stream().anyMatch(text -> text.contains("时间窗")), notices.toString());
    }

    @Test
    void withoutSenderEngineIdNoV3TrapIsReceived() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ""));
        assertEquals(0, registry.usmStackOf(port).userCount());
        sendV3(port, ENGINE, 1, "trapuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        assertRejected();
    }

    @Test
    void releaseRemovesTheLocalizedUser() throws Exception {
        TrapRecipient recipient = recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX);
        int port = freePort();
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);
        TrapListenerHandle first = registry.acquire("dev", "127.0.0.1", port, null, recipient);
        registry.acquire("other", "127.0.0.1", port, null, null);
        TrapUsmStack stack = registry.usmStackOf(port);
        assertEquals(1, stack.userCount());
        first.release();
        assertEquals(0, stack.userCount());
        assertTrue(!stack.tracksEngine(ENGINE));
    }

    @Test
    void v2cTrapToUsmDeviceCountsAuthFailure() throws Exception {
        int port = listen(recipient(SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, ENGINE_HEX));
        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(1)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.4")));
        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            org.snmp4j.CommunityTarget<Address> target = new org.snmp4j.CommunityTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setCommunity(new OctetString("trapuser"));
            target.setVersion(SnmpConstants.version2c);
            snmp.send(pdu, target);
        }
        assertRejected();
        assertTrue(waitFor(() -> counters.authFailure() == 1));
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
