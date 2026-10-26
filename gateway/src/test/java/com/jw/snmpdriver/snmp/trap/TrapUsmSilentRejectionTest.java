package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import org.junit.jupiter.api.Test;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MutablePDU;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportStateReference;
import org.snmp4j.UserTarget;
import org.snmp4j.asn1.BERInputStream;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MutableStateReference;
import org.snmp4j.mp.PduHandle;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.DecryptParams;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * A rejection the SNMP4J dispatcher reports to nobody must be attributed to the sender the dispatcher received
 * it from, not to {@code TransportStateReference.getAddress()}, which on the Gateway is not the sender.
 */
class TrapUsmSilentRejectionTest {
    private static final String ENGINE_HEX = "80001f8880aabbccdd01";
    private static final byte[] ENGINE = HexFormat.of().parseHex(ENGINE_HEX);
    private static final String AUTH = "authpass123";
    private static final String PRIV = "privpass123";

    private record Report(Address peer, int status) {
    }

    /** DES ciphertext one byte too long: the digest still verifies, USM then fails to decrypt (1406). */
    private static final class OddLengthDes extends PrivDES {
        @Override
        public byte[] encrypt(byte[] data, int offset, int length, byte[] key, long boots, long time,
                              DecryptParams params) {
            byte[] encrypted = super.encrypt(data, offset, length, key, boots, time, params);
            return Arrays.copyOf(encrypted, encrypted.length + 1);
        }

        @Override
        public int getEncryptedLength(int scopedPDULength) {
            return super.getEncryptedLength(scopedPDULength) + 1;
        }
    }

    private static byte[] captureUndecryptableTrap() throws Exception {
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new OddLengthDes());
        USM usm = new USM(protocols, new OctetString(ENGINE), 1);
        usm.addUser(new UsmUser(new OctetString("trapuser"), AuthHMAC192SHA256.ID, new OctetString(AUTH),
                PrivDES.ID, new OctetString(PRIV)), new OctetString(ENGINE));
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(ENGINE, null, protocols,
                new SecurityModels().addSecurityModel(usm), CounterSupport.getInstance()));
        try (DatagramSocket capture = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             Snmp snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping())) {
            capture.setSoTimeout(5000);
            snmp.listen();
            UserTarget<Address> target = new UserTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + capture.getLocalPort()));
            target.setVersion(SnmpConstants.version3);
            target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            target.setSecurityName(new OctetString("trapuser"));
            ScopedPDU pdu = new ScopedPDU();
            pdu.setType(PDU.TRAP);
            pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(4200)));
            pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.4")));
            snmp.send(pdu, target);
            DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
            capture.receive(packet);
            return Arrays.copyOf(packet.getData(), packet.getLength());
        }
    }

    @Test
    void usmDecryptionErrorIsReportedForTheDispatcherPeer() throws Exception {
        TrapUsmStack stack = new TrapUsmStack(MPv3.createLocalEngineID(), 1);
        stack.register(TrapV3User.of("trapuser", SNMPDeviceConfig.SecurityLevel.AUTH_PRIV,
                SNMPDeviceConfig.AuthProtocol.SHA256, AUTH, SNMPDeviceConfig.PrivacyProtocol.DES, PRIV, ENGINE_HEX));
        List<Report> reports = new CopyOnWriteArrayList<>();
        stack.setSilentRejectionListener((peer, status, rejection) -> reports.add(new Report(peer, status)));
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(stack.mpv3());

        byte[] message = captureUndecryptableTrap();
        UdpAddress sender = new UdpAddress("10.1.2.3/40000");
        UdpAddress listenerSide = new UdpAddress("0.0.0.0/10162");
        TransportStateReference transport = new TransportStateReference(null, listenerSide, null,
                org.snmp4j.security.SecurityLevel.undefined, org.snmp4j.security.SecurityLevel.undefined, false, null);
        int status = stack.mpv3().prepareDataElements(dispatcher, sender, new BERInputStream(ByteBuffer.wrap(message)),
                transport, new Integer32(), new Integer32(), new OctetString(), new Integer32(), new MutablePDU(),
                new PduHandle(), new Integer32(), new StatusInformation(), new MutableStateReference<>());

        assertEquals(SnmpConstants.SNMPv3_USM_DECRYPTION_ERROR, status);
        assertEquals(1, reports.size(), reports.toString());
        assertNotNull(reports.get(0).peer());
        assertEquals(sender, reports.get(0).peer());
        assertEquals(SnmpConstants.SNMPv3_USM_DECRYPTION_ERROR, reports.get(0).status());
    }
}
