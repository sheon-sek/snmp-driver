package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageDispatcher;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.TransportStateReference;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.mp.StateReference;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.security.UsmTimeEntry;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class Snmp4jKeyCacheTest {
    private static final NumericOid VALUE_OID = NumericOid.parse("1.3.6.1.2.1.1.7.0");
    // Synthetic test-only credentials. Never include them in assertion messages or diagnostics.
    private static final String TEST_SECRET = "cache-test-password";

    @Test
    void hotGetAndSetReuseLocalizedKeysWithoutRepeatingPasswordDerivation() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint endpoint = device.endpoint();
                get(client, endpoint);
                assertEquals(2, auth.calls.get(), "Cold authPriv needs one authentication and one privacy derivation");
                get(client, endpoint);
                get(client, endpoint);
                assertEquals(72, client.set(endpoint, VALUE_OID, new Integer32(72)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).binding().value().toInt());
                assertEquals(2, auth.calls.get(), "Hot GET and SET must reuse both real localized keys");
                assertEquals(4, factory.targets.size(), "Each public request must encode with an independent target");
            }
        }
    }

    @Test
    void changedEngineRecoversThroughInternalReportRetryAndThenReusesNewKeys() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint endpoint = device.endpoint();
                get(client, endpoint);
                device.replaceEngine();
                boolean recovered = false;
                String failureType = "";
                for (int attempt = 0; attempt < 3 && !recovered; attempt++) {
                    try {
                        get(client, endpoint);
                        recovered = true;
                    } catch (java.util.concurrent.ExecutionException expectedDuringRecovery) {
                        failureType = expectedDuringRecovery.getCause().getClass().getSimpleName();
                        // A replacement engine may require more than one exchange. Recovery remains bounded.
                    }
                }
                assertTrue(recovered, "Engine replacement must recover within three polling requests; last failure="
                        + failureType + "; derivations=" + auth.calls.get() + "; learnedNewEngine="
                        + new OctetString(device.engineId).equals(((MPv3) factory.session.getMessageProcessingModel(MPv3.ID))
                        .getEngineID(new UdpAddress(InetAddress.getByName(endpoint.host()), endpoint.port()))));
                assertEquals(4, auth.calls.get(), "Each actual engine needs its own auth and privacy keys");
                get(client, endpoint);
                assertEquals(4, auth.calls.get(), "The recovered engine must remain hot");
            }
        }
    }

    @Test
    void authenticatedTimeWindowReportIsRetriedInternallyWithHotKeys() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint endpoint = device.endpoint();
                get(client, endpoint);
                device.reportNext.set(true);
                int before = device.requests.get();
                get(client, endpoint);
                assertEquals(2, device.requests.get() - before, "One secured REPORT must cause an internal resend");
                assertEquals(2, auth.calls.get(), "Internal retries must reuse the existing engine's keys");
            }
        }
    }

    @Test
    void oldInFlightReportCannotInvalidateTheNewEndpointGeneration() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint oldEndpoint = device.endpoint();
                device.holdNext.set(true);
                var oldRequest = client.getBatch(oldEndpoint, List.of(VALUE_OID)).toCompletableFuture();
                var held = device.held.get(5, TimeUnit.SECONDS);
                SnmpEndpoint replacement = device.endpoint();
                get(client, replacement);
                assertEquals(4, auth.calls.get(), "Equal-valued endpoints represent different generations");
                device.respond(held, SnmpConstants.usmStatsWrongDigests);
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> oldRequest.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof SnmpNoResponseException,
                        "A retired generation's REPORT must be discarded before engine-cache mutation");
                get(client, replacement);
                assertEquals(4, auth.calls.get(), "A late old-generation REPORT must preserve the new generation's keys");
            }
        }
    }

    @Test
    void securityReportInvalidatesOnlyItsCurrentEndpointKeysAndRediscovers() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint endpoint = device.endpoint();
                device.holdNext.set(true);
                var request = client.getBatch(endpoint, List.of(VALUE_OID)).toCompletableFuture();
                device.respond(device.held.get(5, TimeUnit.SECONDS), SnmpConstants.usmStatsWrongDigests);
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> request.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof SnmpSecurityReportException);
                get(client, endpoint);
                assertEquals(4, auth.calls.get(), "A current-generation security failure requires fresh localization");
            }
        }
    }

    @Test
    void concurrentColdDiscoverySharesOneDerivationForTheActualEngine() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                device.holdDiscovery.set(true);
                SnmpEndpoint endpoint = device.endpoint();
                List<CompletableFuture<BatchResult>> requests = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    requests.add(client.getBatch(endpoint, List.of(VALUE_OID)).toCompletableFuture());
                }
                List<Runnable> reports = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    Runnable report = device.discoveryReports.poll(5, TimeUnit.SECONDS);
                    assertNotNull(report, "All cold requests must overlap before any discovery completes");
                    reports.add(report);
                }
                device.holdDiscovery.set(false);
                reports.forEach(Runnable::run);
                for (var request : requests) {
                    assertEquals(72, request.get(5, TimeUnit.SECONDS).bindings().get(0).value().toInt());
                }
                assertEquals(8, factory.targets.size(), "Concurrent requests must retain independent targets");
                assertEquals(2, auth.calls.get(), "Concurrent discovery must derive each actual engine's keys once");
            }
        }
    }

    @Test
    void localizedKeysEvictTheLeastRecentlyUsedEntryAtExactly1024() throws Exception {
        try (Device device = new Device()) {
            LocalizedKeyCache cache = new LocalizedKeyCache();
            SnmpEndpoint endpoint = device.endpoint();
            cache.activate(endpoint);
            CountingAuth auth = new CountingAuth();
            var target = cache.target(endpoint, new UdpAddress("127.0.0.1/161"), auth, null);
            for (int i = 0; i < 1_024; i++) {
                target.setAuthoritativeEngineID(engineId(i));
            }
            assertEquals(1_024, auth.calls.get());
            target.setAuthoritativeEngineID(engineId(0));
            assertEquals(1_024, auth.calls.get(), "A full cache must retain all 1024 entries");
            target.setAuthoritativeEngineID(engineId(1_024));
            target.setAuthoritativeEngineID(engineId(0));
            assertEquals(1_025, auth.calls.get(), "Recently used entries must survive eviction");
            target.setAuthoritativeEngineID(engineId(1));
            assertEquals(1_026, auth.calls.get(), "Entry 1025 must evict the least recently used key");
            cache.close();
        }
    }

    @Test
    void lateOldEngineResponseCannotReplaceTheNewGenerationEngineOrKeys() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint old = device.endpoint(5_000);
                get(client, old);
                device.holdResponse.set(true);
                var pending = client.getBatch(old, List.of(VALUE_OID)).toCompletableFuture();
                Runnable lateResponse = device.outbound.poll(5, TimeUnit.SECONDS);
                assertNotNull(lateResponse);
                device.replaceEngine();
                SnmpEndpoint replacement = device.endpoint();
                // A changed engine REPORT may be rejected by the library before the public callback.
                for (int attempt = 0; ; attempt++) {
                    try {
                        get(client, replacement);
                        break;
                    } catch (java.util.concurrent.ExecutionException error) {
                        if (attempt == 2) throw error;
                    }
                }
                int warmCalls = auth.calls.get();
                lateResponse.run();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(6, TimeUnit.SECONDS));
                MPv3 model = (MPv3) factory.session.getMessageProcessingModel(MPv3.ID);
                assertEquals(new OctetString(device.engineId), model.getEngineID(
                        new UdpAddress(InetAddress.getByName(replacement.host()), replacement.port())));
                get(client, replacement);
                assertEquals(warmCalls, auth.calls.get(), "A retired response must not replace the new generation's engine or keys");
            }
        }
    }

    @Test
    void silentEngineReplacementTimesOutThenRediscoversWithoutBusinessReport() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint endpoint = device.endpoint();
                get(client, endpoint);
                device.replaceEngine();
                device.dropUnknownReports.set(true);
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> client.getBatch(endpoint, List.of(VALUE_OID)).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof SnmpNoResponseException);
                MPv3 model = (MPv3) factory.session.getMessageProcessingModel(MPv3.ID);
                assertEquals(null, model.getEngineID(new UdpAddress(InetAddress.getByName(endpoint.host()), endpoint.port())),
                        "Timeout must discard the stale address-to-engine hint without a business REPORT");
                device.dropUnknownReports.set(false);
                get(client, endpoint);
                get(client, endpoint);
                assertEquals(4, auth.calls.get(), "Rediscovery must select the replacement engine and remain hot");
            }
        }
    }

    @Test
    void differentUsersAndCredentialsNeverBorrowAnExistingUsersLocalizedKeys() throws Exception {
        try (Device device = new Device(); DnsResolver resolver = new DnsResolver()) {
            ClientFactory factory = new ClientFactory();
            try (Snmp4jRequestClient client = new Snmp4jRequestClient(resolver, factory)) {
                CountingAuth auth = factory.countDerivations();
                client.start();
                SnmpEndpoint first = device.endpoint();
                String secondName = "other-test-user";
                device.session.getUSM().addUser(new UsmUser(new OctetString(secondName), AuthHMAC192SHA256.ID,
                        new OctetString(TEST_SECRET), PrivAES128.ID, new OctetString(TEST_SECRET)));
                SnmpEndpoint second = device.endpoint("second-device", secondName, TEST_SECRET, 1_000);
                get(client, first);
                get(client, second);
                get(client, first);
                get(client, second);
                assertEquals(4, auth.calls.get(), "Each security name must own its localized key pair");
                SnmpEndpoint wrongCredentials = device.endpoint("third-device", first.securityName(), "incorrect-test-password", 1_000);
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> client.getBatch(wrongCredentials, List.of(VALUE_OID)).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(6, auth.calls.get(), "Different credentials must be derived independently and fail authentication");
            }
        }
    }

    @Test
    void authenticationAndPrivacyProtocolIdsSelectIndependentLocalizedKeys() throws Exception {
        try (Device device = new Device()) {
            LocalizedKeyCache cache = new LocalizedKeyCache();
            SnmpEndpoint endpoint = device.endpoint();
            cache.activate(endpoint);
            CountingAuth auth = new CountingAuth();
            UdpAddress address = new UdpAddress("127.0.0.1/161");
            var aes128 = cache.target(endpoint, address, auth, new PrivAES128());
            aes128.setAuthoritativeEngineID(device.engineId);
            var aes256 = cache.target(endpoint, address, auth, new PrivAES256());
            aes256.setAuthoritativeEngineID(device.engineId);
            assertEquals(4, auth.calls.get(), "Privacy protocol changes require a separate localized key pair");
            assertEquals(16, aes128.getPrivacyKey().length());
            assertEquals(32, aes256.getPrivacyKey().length());
            var sha512 = cache.target(endpoint, address, new AuthHMAC384SHA512(), new PrivAES256());
            sha512.setAuthoritativeEngineID(device.engineId);
            assertEquals(64, sha512.getAuthenticationKey().length(), "Authentication protocol changes must select the full SHA-512 key");
            aes128.setAuthoritativeEngineID(device.engineId);
            assertEquals(4, auth.calls.get(), "The original protocol combination must remain reusable");
            cache.close();
        }
    }

    private static byte[] engineId(int value) {
        return ByteBuffer.allocate(8).putInt(0x80001370).putInt(value).array();
    }

    private static void get(Snmp4jRequestClient client, SnmpEndpoint endpoint) throws Exception {
        assertEquals(72, client.getBatch(endpoint, List.of(VALUE_OID)).toCompletableFuture()
                .get(5, TimeUnit.SECONDS).bindings().get(0).value().toInt());
    }

    private static final class CountingAuth extends AuthHMAC192SHA256 {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public byte[] passwordToKey(OctetString password, byte[] engineId) {
            calls.incrementAndGet();
            return super.passwordToKey(password, engineId);
        }
    }

    private static final class ClientFactory implements Snmp4jRequestClient.TransportFactory {
        private Snmp session;
        private final Set<Target<?>> targets = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

        @Override
        public DefaultUdpTransportMapping createIpv4() throws IOException {
            return new DefaultUdpTransportMapping() {
                @Override
                public void sendMessage(UdpAddress address, byte[] message, TransportStateReference state,
                                        long timeout, int retries) throws IOException {
                    if (state.getTarget() instanceof UserTarget<?> target && target.getAuthoritativeEngineID().length > 0) {
                        targets.add(target);
                    }
                    super.sendMessage(address, message, state, timeout, retries);
                }
            };
        }

        @Override
        public Snmp createSnmp(DefaultUdpTransportMapping ipv4) throws IOException {
            session = Snmp4jRequestClient.TransportFactory.super.createSnmp(ipv4);
            return session;
        }

        CountingAuth countDerivations() {
            CountingAuth auth = new CountingAuth();
            SecurityProtocols protocols = ((MPv3) session.getMessageProcessingModel(MPv3.ID)).getSecurityProtocols();
            protocols.removeAuthenticationProtocol(auth.getID());
            protocols.addAuthenticationProtocol(auth);
            return auth;
        }
    }

    private static final class Device implements AutoCloseable {
        private final Snmp session;
        private final DefaultUdpTransportMapping transport;
        private byte[] engineId;
        private final AtomicBoolean reportNext = new AtomicBoolean();
        private final AtomicBoolean holdNext = new AtomicBoolean();
        private final AtomicInteger requests = new AtomicInteger();
        private final CompletableFuture<CommandResponderEvent<?>> held = new CompletableFuture<>();
        private final AtomicBoolean holdDiscovery = new AtomicBoolean();
        private final BlockingQueue<Runnable> discoveryReports = new LinkedBlockingQueue<>();
        private final AtomicBoolean holdResponse = new AtomicBoolean();
        private final BlockingQueue<Runnable> outbound = new LinkedBlockingQueue<>();
        private final AtomicBoolean dropUnknownReports = new AtomicBoolean();

        Device() throws Exception {
            SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
            protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
            protocols.addPrivacyProtocol(new PrivAES128());
            engineId = MPv3.createLocalEngineID();
            USM usm = new USM(protocols, new OctetString(engineId), 7);
            usm.setEngineDiscoveryEnabled(false);
            usm.getTimeTable().addEntry(new UsmTimeEntry(new OctetString(engineId), 7, 0));
            usm.addUser(new UsmUser(new OctetString("cache-test-user"), AuthHMAC192SHA256.ID,
                    new OctetString(TEST_SECRET), PrivAES128.ID, new OctetString(TEST_SECRET)));
            MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv3(engineId, null, protocols,
                    new SecurityModels().addSecurityModel(usm), CounterSupport.getInstance()) {
                @Override
                public int sendReport(MessageDispatcher dispatcher, ScopedPDU pdu, int level, int model,
                                      OctetString name, int maxSize, StateReference<?> state, VariableBinding payload) {
                    if (dropUnknownReports.get() && payload.getOid().equals(SnmpConstants.usmStatsUnknownEngineIDs)) {
                        return SnmpConstants.SNMP_MP_OK;
                    }
                    if (holdDiscovery.get() && payload.getOid().equals(SnmpConstants.usmStatsUnknownEngineIDs)) {
                        discoveryReports.add(() -> super.sendReport(dispatcher, pdu, SecurityLevel.NOAUTH_NOPRIV,
                                model, name, maxSize, state, payload));
                        return SnmpConstants.SNMP_MP_OK;
                    }
                    // An authoritative device cannot authenticate an unknown-engine discovery REPORT.
                    return super.sendReport(dispatcher, pdu,
                            payload.getOid().equals(SnmpConstants.usmStatsUnknownEngineIDs)
                                    ? SecurityLevel.NOAUTH_NOPRIV : level,
                            model, name, maxSize, state, payload);
                }
            });
            transport = new DefaultUdpTransportMapping(new UdpAddress(InetAddress.getByName("127.0.0.1"), 0)) {
                @Override
                public void sendMessage(UdpAddress address, byte[] message, TransportStateReference state,
                                        long timeout, int retries) throws IOException {
                    if (holdResponse.compareAndSet(true, false)) {
                        outbound.add(() -> {
                            try {
                                super.sendMessage(address, message, state, timeout, retries);
                            } catch (IOException error) {
                                throw new IllegalStateException("Deferred test response failed", error);
                            }
                        });
                    } else {
                        super.sendMessage(address, message, state, timeout, retries);
                    }
                }
            };
            session = new Snmp(dispatcher, transport);
            session.setLocalEngine(engineId, 7, 0);
            session.addCommandResponder(new CommandResponder() {
                @Override
                public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                    requests.incrementAndGet();
                    if (holdNext.compareAndSet(true, false)) {
                        held.complete(event);
                        return;
                    }
                    try {
                        respond(event, reportNext.compareAndSet(true, false) ? SnmpConstants.usmStatsNotInTimeWindows : null);
                    } catch (IOException error) {
                        throw new IllegalStateException("Test response failed", error);
                    }
                }
            });
            session.listen();
        }

        <A extends Address> void respond(CommandResponderEvent<A> event, org.snmp4j.smi.OID report) throws IOException {
            PDU response = (PDU) event.getPDU().clone();
            response.setType(report == null ? PDU.RESPONSE : PDU.REPORT);
            if (report == null) {
                response.get(0).setVariable(new Integer32(72));
            } else {
                response.clear();
                response.add(new VariableBinding(report, new Counter32(1)));
            }
            event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(), event.getSecurityModel(),
                    event.getSecurityName(), event.getSecurityLevel(), response, event.getMaxSizeResponsePDU(),
                    event.getStateReference(), new StatusInformation());
        }

        SnmpEndpoint endpoint() {
            return endpoint(1_000);
        }

        SnmpEndpoint endpoint(long timeout) {
            return endpoint("device", "cache-test-user", TEST_SECRET, timeout);
        }

        SnmpEndpoint endpoint(String deviceId, String name, String secret, long timeout) {
            return new SnmpEndpoint(deviceId, "127.0.0.1", transport.getListenAddress().getPort(),
                    SNMPDeviceConfig.IpFamily.IPV4, SNMPDeviceConfig.Version.V3, "", "", name,
                    SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256, secret,
                    SNMPDeviceConfig.PrivacyProtocol.AES128, secret, "", SNMPDeviceConfig.AccessMode.READ_WRITE,
                    timeout, 0, 20, 1_200, 1);
        }

        void replaceEngine() {
            session.getUSM().removeEngineTime(new OctetString(engineId));
            engineId = MPv3.createLocalEngineID();
            session.setLocalEngine(engineId, 8, 0);
            session.getUSM().getTimeTable().addEntry(new UsmTimeEntry(new OctetString(engineId), 8, 0));
        }

        @Override
        public void close() throws IOException {
            session.close();
        }
    }
}
