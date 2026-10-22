package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class Snmp4jRequestClientTest {
    private static final NumericOid OID = NumericOid.parse("1.3.6.1.2.1.1.7.0");

    @ParameterizedTest
    @CsvSource({"IPV4, false", "AUTO, false", "IPV4, true", "AUTO, true"})
    void ipv4GetStillReceivesAResponseWhenIpv6Fails(SNMPDeviceConfig.IpFamily family, boolean failOnListen) throws Exception {
        var transport = new DefaultUdpTransportMapping(new UdpAddress(InetAddress.getByName("127.0.0.1"), 0));
        CompletableFuture<PDU> received = new CompletableFuture<>();
        try (Snmp agent = new Snmp(transport);
             DnsResolver resolver = new DnsResolver(host -> new InetAddress[] {
                     InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")});
             Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, withoutIpv6(failOnListen))) {
            agent.addCommandResponder(new CommandResponder() {
                @Override
                public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                    try {
                        PDU response = (PDU) event.getPDU().clone();
                        response.setType(PDU.RESPONSE);
                        response.get(0).setVariable(new Integer32(72));
                        event.getMessageDispatcher().returnResponsePdu(
                                event.getMessageProcessingModel(), event.getSecurityModel(),
                                event.getSecurityName(), event.getSecurityLevel(), response,
                                event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
                        received.complete(event.getPDU());
                    } catch (Exception error) {
                        received.completeExceptionally(error);
                    }
                }
            });
            agent.listen();
            client.start();

            var result = client.getBatch(endpoint("dual-stack.test", transport.getListenAddress().getPort(),
                            family), List.of(OID))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(PDU.GET, received.get(1, TimeUnit.SECONDS).getType());
            assertEquals(72, result.bindings().get(0).value().toInt());
        }
    }

    @Test
    void failedClientCreationClosesEveryAcquiredMapping() throws Exception {
        try (var ipv4 = new TrackingTransport(); var ipv6 = new TrackingTransport();
             DnsResolver resolver = new DnsResolver()) {
            IOException failure = new IOException("Client creation failed");
            var factory = new Snmp4jRequestClient.TransportFactory() {
                @Override
                public DefaultUdpTransportMapping createIpv4() { return ipv4; }
                @Override
                public DefaultUdpTransportMapping createIpv6() { return ipv6; }
                @Override
                public Snmp createSnmp(DefaultUdpTransportMapping transport) throws IOException { throw failure; }
            };

            assertEquals(failure, assertThrows(IOException.class, () -> new Snmp4jRequestClient(resolver, factory)));
            assertTrue(ipv4.socketClosed(), "IPv4 socket must be released before constructor returns failure");
            assertTrue(ipv6.socketClosed(), "IPv6 socket must be released before constructor returns failure");
        }
    }

    private static class TrackingTransport extends DefaultUdpTransportMapping {
        TrackingTransport() throws SocketException { super(); }
        boolean socketClosed() { return socket == null || socket.isClosed(); }
    }

    @Test
    void failedListenerStartupClosesAcquiredMappingsAndRejectsRequests() throws Exception {
        try (var ipv4 = new TrackingTransport(); var ipv6 = new TrackingTransport();
             DnsResolver resolver = new DnsResolver()) {
            IOException failure = new IOException("Listener startup failed");
            boolean[] clientClosed = {false};
            var factory = new Snmp4jRequestClient.TransportFactory() {
                @Override
                public DefaultUdpTransportMapping createIpv4() { return ipv4; }
                @Override
                public DefaultUdpTransportMapping createIpv6() { return ipv6; }
                @Override
                public Snmp createSnmp(DefaultUdpTransportMapping transport) {
                    return new Snmp(new MessageDispatcherImpl(), transport) {
                        @Override
                        public void listen() throws IOException {
                            super.listen();
                            throw failure;
                        }
                        @Override
                        public void close() throws IOException {
                            super.close();
                            clientClosed[0] = true;
                        }
                    };
                }
            };
            try (var client = new Snmp4jRequestClient(resolver, factory)) {
                assertSame(failure, assertThrows(IOException.class, client::start));
                assertTrue(ipv4.socketClosed());
                assertTrue(ipv6.socketClosed());
                assertTrue(clientClosed[0]);
                var requestFailure = assertThrows(CompletionException.class, () -> client.getBatch(
                        endpoint("127.0.0.1", 161, SNMPDeviceConfig.IpFamily.IPV4), List.of(OID))
                        .toCompletableFuture().join());
                assertTrue(requestFailure.getCause() instanceof IllegalStateException);
            }
        }
    }

    @Test
    void laterInitializationFailureClosesClientEvenWhenAMappingCloseThrows() throws Exception {
        try (var ipv4 = new TrackingTransport() {
            private boolean failed;
            @Override
            public void close() throws IOException {
                super.close();
                if (!failed) {
                    failed = true;
                    throw new IOException("Injected mapping close failure");
                }
            }
        }; var ipv6 = new TrackingTransport(); DnsResolver resolver = new DnsResolver()) {
            IllegalStateException failure = new IllegalStateException("Registration failed");
            boolean[] clientClosed = {false};
            Snmp session = new Snmp(new MessageDispatcherImpl(), ipv4) {
                @Override
                public void setLocalEngine(byte[] engineId, int boots, int time) {
                    throw failure;
                }
                @Override
                public void close() throws IOException {
                    super.close();
                    clientClosed[0] = true;
                }
            };
            var factory = new Snmp4jRequestClient.TransportFactory() {
                @Override
                public DefaultUdpTransportMapping createIpv4() { return ipv4; }
                @Override
                public DefaultUdpTransportMapping createIpv6() { return ipv6; }
                @Override
                public Snmp createSnmp(DefaultUdpTransportMapping transport) { return session; }
            };

            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> new Snmp4jRequestClient(resolver, factory)));
            assertTrue(ipv4.socketClosed());
            assertTrue(ipv6.socketClosed());
            assertTrue(clientClosed[0], "Client cleanup must run even when a mapping fails to close");
            assertEquals(1, failure.getSuppressed().length);
        }
    }

    @ParameterizedTest
    @CsvSource({"IPV6, false", "IPV6, true", "AUTO, false", "AUTO, true"})
    void ipv6OnlyDeviceReportsUnavailableTransportForGetAndSet(
            SNMPDeviceConfig.IpFamily family, boolean write) throws Exception {
        try (DnsResolver resolver = new DnsResolver();
             Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, withoutIpv6())) {
            client.start();
            SnmpEndpoint endpoint = endpoint("::1", 161, family);
            var error = assertThrows(Exception.class, () -> {
                var request = write ? client.set(endpoint, OID, new Integer32(1))
                        : client.getBatch(endpoint, List.of(OID));
                request.toCompletableFuture().get(5, TimeUnit.SECONDS);
            });

            var failure = SnmpFailureClassifier.classify(error);
            assertEquals("transport-unavailable", failure.category());
            assertEquals(StatusCodes.Bad_NoCommunication, failure.statusCode());
            assertTrue(failure.message().contains("IPv6 transport is unavailable"));
            assertFalse(failure.circuitFailure(), "Local transport absence must not advance network backoff");
            assertFalse(failure.legalResponse());
            assertFalse(failure.unexpected(), "Transport absence must degrade rather than become Errored");
        }
    }

    private static Snmp4jRequestClient.TransportFactory withoutIpv6() {
        return withoutIpv6(false);
    }

    private static Snmp4jRequestClient.TransportFactory withoutIpv6(boolean failOnListen) {
        return new Snmp4jRequestClient.TransportFactory() {
            @Override
            public DefaultUdpTransportMapping createIpv6() throws IOException {
                if (failOnListen) {
                    return new DefaultUdpTransportMapping() {
                        @Override
                        public void listen() throws IOException { throw new IOException("IPv6 listener unavailable"); }
                    };
                }
                throw new IOException("IPv6 disabled for test");
            }
        };
    }

    static SnmpEndpoint endpoint(String host, int port, SNMPDeviceConfig.IpFamily family) {
        return new SnmpEndpoint("device", host, port, family, SNMPDeviceConfig.Version.V2C,
                "test-community", "test-community", "", SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV,
                SNMPDeviceConfig.AuthProtocol.SHA256, "", SNMPDeviceConfig.PrivacyProtocol.AES128,
                "", "", SNMPDeviceConfig.AccessMode.READ_WRITE, 1_000, 0, 20, 1_200, 1);
    }
}
