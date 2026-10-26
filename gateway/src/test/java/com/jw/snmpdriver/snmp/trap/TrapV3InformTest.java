package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmTimeEntry;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * v3 Informs from an in-process SNMP4J sender over real loopback UDP. The Gateway is the authoritative engine:
 * the sender discovers {@code Traps/LocalEngineId}, and the local time window applies (RFC 3414).
 */
class TrapV3InformTest {
    private static final String SENDER_HEX = "80001f8880aabbccdd02";
    private static final byte[] SENDER = HexFormat.of().parseHex(SENDER_HEX);
    private static final String AUTH = "authpass123";
    private static final String PRIV = "privpass123";

    @TempDir
    Path dir;

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private TrapListenerRegistry registry;
    private Snmp sender;
    private USM senderUsm;
    private final BlockingQueue<TrapNotification> sink = new LinkedBlockingQueue<>();
    private final TrapCounters counters = new TrapCounters();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private int port;

    {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sender != null) {
            sender.close();
        }
        if (registry != null) {
            registry.closeAll();
        }
        scheduler.shutdownNow();
    }

    private TrapEngineIdentity identity() {
        return TrapEngineIdentity.load(dir.resolve(TrapEngineIdentity.FILE_NAME), Map::of,
                MPv3::createLocalEngineID, Clock.systemUTC());
    }

    /** An Inform-only USM Device unless {@code senderEngineHex} is set. */
    private void listen(TrapEngineIdentity identity, String senderEngineHex) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        TrapV3User user = TrapV3User.of("informuser", SNMPDeviceConfig.SecurityLevel.AUTH_PRIV,
                SNMPDeviceConfig.AuthProtocol.SHA256, AUTH, SNMPDeviceConfig.PrivacyProtocol.AES128, PRIV,
                senderEngineHex);
        registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler,
                () -> LicenseGate.Verdict.LICENSED, identity);
        registry.acquire("dev", "127.0.0.1", port, null, new TrapRecipient("dev",
                new TrapFilter("127.0.0.1", "", new byte[0], InetAddress::getAllByName), counters,
                new TokenBucket(1000), sink::add, notices::add, true, user));
    }

    private Address gateway() {
        return GenericAddress.parse("udp:127.0.0.1/" + port);
    }

    /** Discovers the Gateway engine and localizes the sender's user to it, as a real Inform sender does. */
    private byte[] discover() throws Exception {
        return discover("informuser", AUTH);
    }

    private byte[] discover(String userName, String auth) throws Exception {
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        senderUsm = new USM(protocols, new OctetString(SENDER), 1);
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(SENDER, null, protocols,
                new SecurityModels().addSecurityModel(senderUsm), CounterSupport.getInstance()));
        sender = new Snmp(dispatcher, new DefaultUdpTransportMapping());
        sender.listen();
        byte[] engine = sender.discoverAuthoritativeEngineID(gateway(), 2000);
        assertNotNull(engine, "no discovery report");
        senderUsm.addUser(new UsmUser(new OctetString(userName), AuthHMAC192SHA256.ID, new OctetString(auth),
                PrivAES128.ID, new OctetString(PRIV)), new OctetString(engine));
        return engine;
    }

    private ResponseEvent<Address> inform(int requestId) throws Exception {
        return inform(requestId, "informuser", SecurityLevel.AUTH_PRIV);
    }

    private ResponseEvent<Address> inform(int requestId, String userName, int level) throws Exception {
        UserTarget<Address> target = new UserTarget<>();
        target.setAddress(gateway());
        target.setVersion(SnmpConstants.version3);
        target.setSecurityLevel(level);
        target.setSecurityName(new OctetString(userName));
        target.setRetries(0);
        target.setTimeout(1500);
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(PDU.INFORM);
        pdu.setRequestID(new Integer32(requestId));
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(4200)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        return sender.send(pdu, target);
    }

    private static boolean acked(ResponseEvent<Address> event) {
        return event != null && event.getResponse() != null && event.getResponse().getType() == PDU.RESPONSE;
    }

    @Test
    void senderDiscoveryReturnsTheLocalEngineIdWithoutTrackingTheSender() throws Exception {
        TrapEngineIdentity identity = identity();
        listen(identity, "");
        byte[] engine = discover();
        assertArrayEquals(identity.engineId(), engine);
        assertEquals(identity.engineIdHex(), HexFormat.of().formatHex(engine));
        // the Gateway answered as authoritative: the sender's own engine never entered its time table
        assertFalse(registry.usmStackOf(port).tracksEngine(SENDER));
        assertFalse(registry.usmStackOf(port).usm().isEngineDiscoveryEnabled());
    }

    /** The Device configuration the system test uses: Trap sender engine set and Inform accepted (#41). */
    @Test
    void wrongPrivPassphraseTrapOnAnInformEnabledStackCountsAuthFailure() throws Exception {
        listen(identity(), SENDER_HEX);
        TrapV3ReceiveTest.sendV3(port, SENDER, 1, "informuser", org.snmp4j.security.SecurityLevel.AUTH_PRIV, AUTH,
                "wrongpriv99");
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertEquals(0, counters.received());
    }

    @Test
    void v3InformIsAckedAfterEnqueueAndARetransmissionOnlyCountsAsDuplicate() throws Exception {
        listen(identity(), "");
        discover();
        assertTrue(acked(inform(4242)), "authFailure=" + counters.authFailure() + " notices=" + notices);
        TrapNotification n = sink.poll(5, TimeUnit.SECONDS);
        assertNotNull(n);
        assertEquals(TrapNotification.Version.V3, n.version());
        assertEquals(TrapNotification.PduType.INFORM, n.pduType());
        assertEquals("1.3.6.1.6.3.1.1.5.3", n.trapOid());

        assertTrue(acked(inform(4242)), "a retransmission is ACKed again");
        assertNull(sink.poll(500, TimeUnit.MILLISECONDS));
        assertEquals(1, counters.duplicateInform());
        assertEquals(0, counters.authFailure());

        assertTrue(acked(inform(4243)));
        assertNotNull(sink.poll(5, TimeUnit.SECONDS));
        assertEquals(1, counters.duplicateInform());
    }

    @Test
    void identityThatCannotBePersistedRefusesV3InformButKeepsV3Trap() throws Exception {
        TrapEngineIdentity identity = TrapEngineIdentity.unpersisted(MPv3.createLocalEngineID(),
                "无法写入 Trap 本地引擎身份文件，v3 Inform 已停用。");
        assertFalse(identity.informAvailable());
        listen(identity, SENDER_HEX);
        discover();
        assertFalse(acked(inform(1)));
        assertNull(sink.poll(500, TimeUnit.MILLISECONDS));
        assertTrue(notices.contains(identity.notice()), notices.toString());

        TrapV3ReceiveTest.sendV3(port, SENDER, 1, "informuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        TrapNotification trap = sink.poll(5, TimeUnit.SECONDS);
        assertNotNull(trap, "v3 Trap must not depend on the local identity; notices=" + notices);
        assertEquals(TrapNotification.PduType.V2_TRAP, trap.pduType());
    }

    @Test
    void engineBootsAtTheLimitRefusesV3InformWithTheReason() throws Exception {
        Path file = dir.resolve(TrapEngineIdentity.FILE_NAME);
        Files.writeString(file, "engineId=80001f8880eeff0011\nengineBoots=2147483646\nmachineFingerprint=\n");
        TrapEngineIdentity identity = identity();
        assertEquals(Integer.MAX_VALUE, identity.engineBoots());
        listen(identity, "");
        discover();
        assertFalse(acked(inform(1)));
        assertNull(sink.poll(500, TimeUnit.MILLISECONDS));
        assertTrue(waitFor(() -> notices.stream().anyMatch(text -> text.contains("2147483647"))),
                notices.toString());
    }

    @Test
    void v3InformIsCheckedAgainstTheLocalTimeWindowNotTheSenders() throws Exception {
        TrapEngineIdentity identity = identity();
        listen(identity, SENDER_HEX);
        byte[] engine = discover();
        // a sender holding stale Gateway boots: no resync, so the stale message is what reaches the Gateway
        sender.setReportHandler(new Snmp.ReportHandler() {
            @Override
            public <A extends Address> void processReport(org.snmp4j.mp.PduHandle handle,
                                                          org.snmp4j.CommandResponderEvent<A> event) {
            }
        });
        senderUsm.getTimeTable().addEntry(new UsmTimeEntry(new OctetString(engine), identity.engineBoots() + 1, 0));
        assertFalse(acked(inform(7)));
        assertNull(sink.poll(500, TimeUnit.MILLISECONDS));
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertTrue(notices.stream().anyMatch(text -> text.contains("Gateway 本地时间窗")), notices.toString());

        // the sender-authoritative window of v3 Trap is separate: its own boots are still accepted
        TrapV3ReceiveTest.sendV3(port, SENDER, 3, "informuser", SecurityLevel.AUTH_PRIV, AUTH, PRIV);
        assertNotNull(sink.poll(5, TimeUnit.SECONDS), "notices=" + notices);
    }

    /** The sender keeps its (0, 0) view of the Gateway engine: no Report makes it resync. */
    private void sendAsFirstTimeSync(byte[] engine) {
        sender.setReportHandler(new Snmp.ReportHandler() {
            @Override
            public <A extends Address> void processReport(org.snmp4j.mp.PduHandle handle,
                                                          org.snmp4j.CommandResponderEvent<A> event) {
            }
        });
        senderUsm.getTimeTable().addEntry(new UsmTimeEntry(new OctetString(engine), 0, 0));
    }

    @Test
    void wrongDigestDuringTimeSyncIsAnAuthenticationFailure() throws Exception {
        listen(identity(), "");
        sendAsFirstTimeSync(discover("informuser", "wrongpass99"));
        assertFalse(acked(inform(1)));
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertFalse(notices.isEmpty(), "LastError must be set");
    }

    @Test
    void unknownUserDuringTimeSyncIsAnAuthenticationFailure() throws Exception {
        listen(identity(), "");
        sendAsFirstTimeSync(discover("nobody", AUTH));
        assertFalse(acked(inform(1, "nobody", SecurityLevel.AUTH_PRIV)));
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertFalse(notices.isEmpty(), "LastError must be set");
    }

    @Test
    void unsupportedSecurityLevelDuringTimeSyncIsAnAuthenticationFailure() throws Exception {
        listen(identity(), "");
        sendAsFirstTimeSync(discover());
        assertFalse(acked(inform(1, "informuser", SecurityLevel.NOAUTH_NOPRIV)));
        assertTrue(waitFor(() -> counters.authFailure() == 1), "authFailure=" + counters.authFailure());
        assertFalse(notices.isEmpty(), "LastError must be set");
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
