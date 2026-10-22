package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocol;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class Snmp4jSecurityIsolationTest {
    private static final NumericOid VALUE_OID = NumericOid.parse("1.3.6.1.2.1.1.1.0");

    @Test
    void clientLifecyclePreservesExistingGlobalSecurityRegistrations() throws Exception {
        SecurityModels globalModels = SecurityModels.getInstance();
        Integer32 usmId = new Integer32(USM.SECURITY_MODEL_USM);
        SecurityModel previous = globalModels.getSecurityModel(usmId);
        USM existing = new USM(new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none),
                new OctetString(MPv3.createLocalEngineID()), 0);
        globalModels.addSecurityModel(existing);
        Map<OID, SecurityProtocol> protocols = globalProtocols();
        try (DnsResolver resolver = new DnsResolver()) {
            try (Snmp4jRequestClient first = new Snmp4jRequestClient(resolver);
                 Snmp4jRequestClient second = new Snmp4jRequestClient(resolver)) {
                first.start();
                second.start();
                assertSame(existing, globalModels.getSecurityModel(usmId));
                assertEquals(protocols, globalProtocols());
            }
            assertSame(existing, globalModels.getSecurityModel(usmId));
            assertEquals(protocols, globalProtocols());
        } finally {
            if (previous == null) {
                globalModels.removeSecurityModel(usmId);
            } else {
                globalModels.addSecurityModel(previous);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "none, NO_AUTH_NO_PRIV, SHA256, AES128",
            "none, AUTH_NO_PRIV, SHA256, AES128",
            "none, AUTH_PRIV, SHA256, AES128",
            "none, AUTH_PRIV, SHA512, AES256",
            "defaultSecurity, AUTH_PRIV, SHA512, AES256"
    })
    void independentClientsKeepReceivingV3ResponsesAcrossOtherClientLifecycle(
            SecurityProtocols.SecurityProtocolSet globalProtocolSet,
            SNMPDeviceConfig.SecurityLevel level,
            SNMPDeviceConfig.AuthProtocol authentication,
            SNMPDeviceConfig.PrivacyProtocol privacy
    ) throws Exception {
        SecurityProtocols previous = SecurityProtocols.getInstance();
        SecurityProtocols global = new SecurityProtocols(globalProtocolSet);
        SecurityProtocols.setSecurityProtocols(global);
        Map<OID, SecurityProtocol> expectedProtocols = globalProtocols();
        try (DnsResolver resolver = new DnsResolver();
             V3Device firstDevice = new V3Device(level, authentication, privacy, "first-test-password", "first:");
             V3Device secondDevice = new V3Device(level, authentication, privacy, "second-test-password", "second:")) {
            CapturingTransportFactory firstFactory = new CapturingTransportFactory();
            try (Snmp4jRequestClient first = new Snmp4jRequestClient(resolver, firstFactory)) {
                first.start();
                USM firstUsm = firstFactory.session.getUSM();
                assertResponse(first, firstDevice);

                CapturingTransportFactory secondFactory = new CapturingTransportFactory();
                try (Snmp4jRequestClient second = new Snmp4jRequestClient(resolver, secondFactory)) {
                    second.start();
                    assertSame(firstUsm, firstFactory.session.getUSM());
                    assertNotSame(firstUsm, secondFactory.session.getUSM());
                    MPv3 firstModel = (MPv3) firstFactory.session.getMessageProcessingModel(MPv3.ID);
                    MPv3 secondModel = (MPv3) secondFactory.session.getMessageProcessingModel(MPv3.ID);
                    assertNotSame(firstModel, secondModel);
                    assertNotSame(firstModel.getSecurityModels(), secondModel.getSecurityModels());
                    assertNotSame(SecurityModels.getInstance(), firstModel.getSecurityModels());
                    assertNotSame(global, firstModel.getSecurityProtocols());
                    assertNotSame(firstModel.getSecurityProtocols(), secondModel.getSecurityProtocols());

                    var firstRequest = first.getBatch(firstDevice.endpoint, List.of(VALUE_OID)).toCompletableFuture();
                    var secondRequest = second.getBatch(secondDevice.endpoint, List.of(VALUE_OID)).toCompletableFuture();
                    assertEquals(firstDevice.value, firstRequest.get(5, TimeUnit.SECONDS).bindings().get(0).value());
                    assertEquals(secondDevice.value, secondRequest.get(5, TimeUnit.SECONDS).bindings().get(0).value());
                }
                assertResponse(first, firstDevice);
                assertSame(firstUsm, firstFactory.session.getUSM());
            }
            assertSame(global, SecurityProtocols.getInstance());
            assertEquals(expectedProtocols, globalProtocols());
        } finally {
            SecurityProtocols.setSecurityProtocols(previous);
        }
    }

    private static void assertResponse(Snmp4jRequestClient client, V3Device device) throws Exception {
        var result = client.getBatch(device.endpoint, List.of(VALUE_OID)).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(0, result.errorStatus());
        assertEquals(VALUE_OID, result.bindings().get(0).oid());
        assertEquals(device.value, result.bindings().get(0).value());
        assertEquals(PDU.GET, device.received.get(1, TimeUnit.SECONDS).getType());
    }

    private static class CapturingTransportFactory implements Snmp4jRequestClient.TransportFactory {
        private Snmp session;

        @Override
        public Snmp createSnmp(DefaultUdpTransportMapping ipv4) throws IOException {
            session = Snmp4jRequestClient.TransportFactory.super.createSnmp(ipv4);
            return session;
        }
    }

    private static class V3Device implements AutoCloseable {
        private final Snmp session;
        private final SnmpEndpoint endpoint;
        private final OctetString value;
        private final CompletableFuture<PDU> received = new CompletableFuture<>();

        V3Device(SNMPDeviceConfig.SecurityLevel level, SNMPDeviceConfig.AuthProtocol authentication,
                 SNMPDeviceConfig.PrivacyProtocol privacy, String password, String prefix) throws Exception {
            // A sizeable response exercises the residual global USM size estimate with empty/default registries.
            value = new OctetString(prefix + "x".repeat(600));
            SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
            protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
            protocols.addAuthenticationProtocol(new AuthHMAC384SHA512());
            protocols.addPrivacyProtocol(new PrivAES128());
            protocols.addPrivacyProtocol(new PrivAES256());
            byte[] engineId = MPv3.createLocalEngineID();
            USM usm = new USM(protocols, new OctetString(engineId), 7);
            boolean authenticated = level != SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV;
            boolean encrypted = level == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV;
            usm.addUser(new UsmUser(new OctetString("shared-test-user"),
                    authenticated ? (authentication == SNMPDeviceConfig.AuthProtocol.SHA256
                            ? AuthHMAC192SHA256.ID : AuthHMAC384SHA512.ID) : null,
                    authenticated ? new OctetString(password) : null,
                    encrypted ? (privacy == SNMPDeviceConfig.PrivacyProtocol.AES128
                            ? PrivAES128.ID : PrivAES256.ID) : null,
                    encrypted ? new OctetString(password) : null));
            var dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv3(engineId, null, protocols,
                    new SecurityModels().addSecurityModel(usm), CounterSupport.getInstance()));
            var transport = new DefaultUdpTransportMapping(new UdpAddress(InetAddress.getByName("127.0.0.1"), 0));
            session = new Snmp(dispatcher, transport);
            session.setLocalEngine(engineId, 7, 0);
            session.addCommandResponder(new CommandResponder() {
                @Override
                public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                    try {
                        PDU response = (PDU) event.getPDU().clone();
                        response.setType(PDU.RESPONSE);
                        response.get(0).setVariable(value);
                        event.getMessageDispatcher().returnResponsePdu(
                                event.getMessageProcessingModel(), event.getSecurityModel(), event.getSecurityName(),
                                event.getSecurityLevel(), response, event.getMaxSizeResponsePDU(),
                                event.getStateReference(), new StatusInformation());
                        received.complete(event.getPDU());
                    } catch (Exception error) {
                        received.completeExceptionally(error);
                    }
                }
            });
            try {
                session.listen();
            } catch (Exception error) {
                session.close();
                throw error;
            }
            endpoint = new SnmpEndpoint(prefix, "127.0.0.1", transport.getListenAddress().getPort(),
                    SNMPDeviceConfig.IpFamily.IPV4, SNMPDeviceConfig.Version.V3, "", "", "shared-test-user", level,
                    authentication, authenticated ? password : "", privacy, encrypted ? password : "", "",
                    SNMPDeviceConfig.AccessMode.READ_WRITE, 1_000, 0, 20, 1_200, 1);
        }

        @Override
        public void close() throws IOException {
            session.close();
        }
    }

    private static Map<OID, SecurityProtocol> globalProtocols() {
        SecurityProtocols protocols = SecurityProtocols.getInstance();
        Map<OID, SecurityProtocol> result = new HashMap<>();
        for (var type : SecurityProtocols.SecurityProtocolType.values()) {
            for (OID id : protocols.getSecurityProtocolOIDs(type)) {
                result.put(id, protocols.getSecurityProtocol(id));
            }
        }
        return result;
    }
}
