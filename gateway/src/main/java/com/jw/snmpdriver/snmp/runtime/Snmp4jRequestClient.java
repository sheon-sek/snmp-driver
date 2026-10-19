package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.snmp4j.AbstractTarget;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcher;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MutablePDU;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.TransportStateReference;
import org.snmp4j.UserTarget;
import org.snmp4j.asn1.BERInputStream;
import org.snmp4j.asn1.BEROutputStream;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.event.ResponseListener;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MessageProcessingModel;
import org.snmp4j.mp.MutableStateReference;
import org.snmp4j.mp.PduHandle;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthHMAC128SHA224;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC256SHA384;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.AuthenticationProtocol;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.PrivacyProtocol;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityParameters;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.SecurityStateReference;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmSecurityStateReference;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Snmp4jRequestClient implements SnmpRequestClient {
    private static final Logger logger = LoggerFactory.getLogger(Snmp4jRequestClient.class);

    private final DnsResolver dnsResolver;
    private final DefaultUdpTransportMapping ipv4Transport;
    private final DefaultUdpTransportMapping ipv6Transport;
    private final Snmp snmp;
    private final SecurityProtocols securityProtocols;
    private final LocalizedKeyCache localizedKeys = new LocalizedKeyCache();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean ipv6Available;
    private boolean closed;

    public Snmp4jRequestClient(DnsResolver dnsResolver) throws IOException {
        this(dnsResolver, new TransportFactory() {});
    }

    Snmp4jRequestClient(DnsResolver dnsResolver, TransportFactory factory) throws IOException {
        this.dnsResolver = dnsResolver;
        DefaultUdpTransportMapping ipv4 = null;
        DefaultUdpTransportMapping ipv6 = null;
        Snmp client = null;
        try {
            this.ipv4Transport = ipv4 = factory.createIpv4();
            try {
                ipv6 = factory.createIpv6();
            } catch (IOException | UnsupportedOperationException | SecurityException error) {
                logger.warn("SNMP IPv6 transport unavailable; IPv4 remains enabled", error);
            }
            this.ipv6Transport = ipv6;
            this.ipv6Available = ipv6 != null;
            this.snmp = client = factory.createSnmp(ipv4Transport);
            if (ipv6Transport != null) {
                this.snmp.addTransportMapping(ipv6Transport);
            }
            this.securityProtocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none)
                    .addDefaultProtocols();
            securityProtocols.addAuthenticationProtocol(new AuthMD5());
            securityProtocols.addAuthenticationProtocol(new AuthSHA());
            securityProtocols.addAuthenticationProtocol(new AuthHMAC128SHA224());
            securityProtocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
            securityProtocols.addAuthenticationProtocol(new AuthHMAC256SHA384());
            securityProtocols.addAuthenticationProtocol(new AuthHMAC384SHA512());
            securityProtocols.addPrivacyProtocol(new PrivDES());
            securityProtocols.addPrivacyProtocol(new PrivAES128());
            securityProtocols.addPrivacyProtocol(new PrivAES192());
            securityProtocols.addPrivacyProtocol(new PrivAES256());

            byte[] localEngineId = MPv3.createLocalEngineID();
            USM usm = new USM(securityProtocols, new OctetString(localEngineId), 0) {
                @Override
                public SecurityStateReference newSecurityStateReference() {
                    return new GenerationSecurityState();
                }
            };
            SecurityModels securityModels = new SecurityModels().addSecurityModel(usm);
            var dispatcher = snmp.getMessageDispatcher();
            dispatcher.addMessageProcessingModel(new MPv1());
            dispatcher.addMessageProcessingModel(new MPv2c());
            dispatcher.addMessageProcessingModel(new KeyAwareMPv3(localEngineId, securityProtocols, securityModels, localizedKeys));
            snmp.setLocalEngine(localEngineId, 0, 0);
        } catch (IOException | RuntimeException | Error error) {
            closeResources(client, ipv4, ipv6, error);
            throw error;
        }
    }

    private static void closeResources(
            Snmp client,
            DefaultUdpTransportMapping ipv4,
            DefaultUdpTransportMapping ipv6,
            Throwable failure
    ) {
        // Snmp.close stops at the first mapping exception, before releasing its timer and dispatcher.
        // Detach mappings so each socket and the session receive an independent cleanup attempt.
        if (client != null) {
            if (ipv4 != null) {
                closeAfterFailure(() -> client.removeTransportMapping(ipv4), failure);
            }
            if (ipv6 != null) {
                closeAfterFailure(() -> client.removeTransportMapping(ipv6), failure);
            }
        }
        closeAfterFailure(ipv4, failure);
        closeAfterFailure(ipv6, failure);
        closeAfterFailure(client, failure);
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception cleanupError) {
                failure.addSuppressed(cleanupError);
            }
        }
    }

    interface TransportFactory {
        default DefaultUdpTransportMapping createIpv4() throws IOException {
            return new DefaultUdpTransportMapping();
        }

        default DefaultUdpTransportMapping createIpv6() throws IOException {
            return new DefaultUdpTransportMapping(new UdpAddress(InetAddress.getByName("::"), 0));
        }

        default Snmp createSnmp(DefaultUdpTransportMapping ipv4) throws IOException {
            // The convenience constructor installs global security protocols and an MPv3 using global models.
            // Return an unconfigured dispatcher so the client can install its own models before listening.
            return new Snmp(new MessageDispatcherImpl() {
                @Override
                protected void configureAuthoritativeEngineID(Target<?> target, MessageProcessingModel model) {
                    // An in-flight target must never write its old engine back over a freshly received REPORT.
                    // KeyAwareMPv3 selects the actual engine and its keys together when encoding each attempt.
                    if (!(target instanceof LocalizedKeyCache.CachedTarget)) {
                        super.configureAuthoritativeEngineID(target, model);
                    }
                }
            }, ipv4);
        }
    }

    private static final class KeyAwareMPv3 extends MPv3 {
        private final LocalizedKeyCache keys;

        KeyAwareMPv3(byte[] engineId, SecurityProtocols protocols, SecurityModels models, LocalizedKeyCache keys) {
            super(engineId, null, protocols, models, CounterSupport.getInstance());
            this.keys = keys;
        }

        @Override
        public synchronized <A extends Address> int prepareDataElements(
                MessageDispatcher dispatcher, A address, BERInputStream message, TransportStateReference transport,
                Integer32 model, Integer32 securityModel, OctetString name, Integer32 level, MutablePDU pdu,
                PduHandle handle, Integer32 maxSize, StatusInformation status, MutableStateReference<A> state) {
            synchronized (keys) {
                // Generation activation cannot interleave between the stale-response check and MPv3's engine write.
                return super.prepareDataElements(dispatcher, address, message, transport, model, securityModel,
                        name, level, pdu, handle, maxSize, status, state);
            }
        }

        @Override
        public int extractScopedPDU(BERInputStream message, SecurityModel model, TransportStateReference transport,
                                   HeaderData header, Integer32 level, Integer32 maxSize, StatusInformation status,
                                   OctetString name, OctetString engine, Integer32 version, SecurityParameters parameters,
                                   BEROutputStream scoped, SecurityStateReference state) throws IOException {
            // MPv3 otherwise learns the engine before validating a response. Reject retired requests before that write.
            if (state instanceof GenerationSecurityState generation && generation.endpoint != null
                    && !keys.isCurrent(generation.endpoint)) {
                return SnmpConstants.SNMP_MP_MATCH_ERROR;
            }
            return super.extractScopedPDU(message, model, transport, header, level, maxSize, status,
                    name, engine, version, parameters, scoped, state);
        }

        @Override
        public synchronized boolean addEngineID(Address address, OctetString engineId) {
            return super.addEngineID(address, engineId);
        }

        @Override
        public synchronized OctetString removeEngineID(Address address) {
            return super.removeEngineID(address);
        }

        @Override
        public synchronized <A extends Address> int prepareOutgoingMessage(
                A address, int maxMessageSize, int model, int securityModel, byte[] securityName,
                int securityLevel, PDU pdu, boolean expectResponse, PduHandle handle,
                BEROutputStream message, TransportStateReference state) throws IOException {
            if (state.getTarget() instanceof LocalizedKeyCache.CachedTarget target) {
                OctetString actual = getEngineID(address);
                OctetString previous = new OctetString(target.getAuthoritativeEngineID());
                byte[] engineId = actual == null ? new byte[0] : actual.getValue();
                target.setAuthoritativeEngineID(engineId);
                if (pdu instanceof ScopedPDU scoped && scoped.getContextEngineID().equals(previous)) {
                    scoped.setContextEngineID(new OctetString(engineId));
                }
            }
            // Pin the engine snapshot through key selection and encoding, including internal REPORT retries.
            return super.prepareOutgoingMessage(address, maxMessageSize, model, securityModel, securityName,
                    securityLevel, pdu, expectResponse, handle, message, state);
        }
    }

    private static final class GenerationSecurityState extends UsmSecurityStateReference {
        private SnmpEndpoint endpoint;

        @Override
        public boolean applyTargetSecurityInformation(Target<?> target) {
            boolean applied = super.applyTargetSecurityInformation(target);
            if (target instanceof LocalizedKeyCache.CachedTarget cached) {
                endpoint = cached.endpoint();
            }
            return applied;
        }
    }

    @Override
    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("SNMP transport is closed");
        }
        if (!started.get()) {
            try {
                ipv4Transport.listen();
                if (ipv6Available) {
                    try {
                        ipv6Transport.listen();
                    } catch (IOException | UnsupportedOperationException | SecurityException error) {
                        ipv6Available = false;
                        snmp.removeTransportMapping(ipv6Transport);
                        closeAfterFailure(ipv6Transport, error);
                        logger.warn("SNMP IPv6 listener unavailable; IPv4 remains enabled", error);
                    }
                }
                snmp.listen();
                started.set(true);
                logger.info("Shared SNMP UDP transport started");
            } catch (IOException | RuntimeException | Error error) {
                closed = true;
                closeResources(snmp, ipv4Transport, ipv6Transport, error);
                throw error;
            }
        }
    }

    @Override
    public CompletionStage<BatchResult> getBatch(
            SnmpEndpoint endpoint,
            List<NumericOid> oids
    ) {
        if (!started.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SNMP transport is not started"));
        }
        if (oids == null || oids.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("SNMP GET batch cannot be empty"));
        }
        if (endpoint.version() == SNMPDeviceConfig.Version.V3) {
            localizedKeys.activate(endpoint);
        }
        return dnsResolver.resolve(endpoint, ipv6Available)
                .thenCompose(address -> sendGet(endpoint, List.copyOf(oids), address));
    }

    @Override
    public CompletionStage<SetResult> set(
            SnmpEndpoint endpoint,
            NumericOid oid,
            Variable value
    ) {
        if (!started.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SNMP transport is not started"));
        }
        if (endpoint.version() == SNMPDeviceConfig.Version.V3) {
            localizedKeys.activate(endpoint);
        }
        return dnsResolver.resolve(endpoint, ipv6Available)
                .thenCompose(address -> sendSet(endpoint, oid, value, address));
    }

    private CompletionStage<BatchResult> sendGet(
            SnmpEndpoint endpoint,
            List<NumericOid> oids,
            UdpAddress address
    ) {
        CompletableFuture<BatchResult> future = new CompletableFuture<>();
        PDU request = createPdu(endpoint);
        request.setType(PDU.GET);
        oids.forEach(oid -> request.add(new VariableBinding(new OID(oid.canonical()))));
        AbstractTarget<UdpAddress> target = createTarget(endpoint, address, false);

        ResponseListener listener = new ResponseListener() {
            @Override
            public <A extends Address> void onResponse(ResponseEvent<A> event) {
                try {
                    cancelRequest(event, this, endpoint, "GET");
                    invalidateFailedAttempt(event, target);
                    completeGet(future, event, endpoint, target);
                } catch (RuntimeException error) {
                    future.completeExceptionally(error);
                }
            }
        };

        try {
            logger.debug(
                    "Dispatch SNMP GET device={} host={} port={} version={} oidCount={}",
                    endpoint.deviceId(), endpoint.host(), endpoint.port(), endpoint.version(), oids.size()
            );
            snmp.get(request, target, null, listener);
        } catch (IOException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private CompletionStage<SetResult> sendSet(
            SnmpEndpoint endpoint,
            NumericOid oid,
            Variable value,
            UdpAddress address
    ) {
        CompletableFuture<SetResult> future = new CompletableFuture<>();
        PDU request = createPdu(endpoint);
        request.setType(PDU.SET);
        request.add(new VariableBinding(new OID(oid.canonical()), value));
        AbstractTarget<UdpAddress> target = createTarget(endpoint, address, true);

        ResponseListener listener = new ResponseListener() {
            @Override
            public <A extends Address> void onResponse(ResponseEvent<A> event) {
                try {
                    cancelRequest(event, this, endpoint, "SET");
                    invalidateFailedAttempt(event, target);
                    completeSet(future, event, endpoint, target, oid);
                } catch (RuntimeException error) {
                    future.completeExceptionally(error);
                }
            }
        };

        try {
            logger.debug(
                    "Dispatch SNMP SET device={} host={} port={} version={} oid={}",
                    endpoint.deviceId(), endpoint.host(), endpoint.port(), endpoint.version(), oid.canonical()
            );
            snmp.set(request, target, null, listener);
        } catch (IOException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private void invalidateFailedAttempt(ResponseEvent<?> event, AbstractTarget<UdpAddress> target) {
        if (!(target instanceof LocalizedKeyCache.CachedTarget cached)) {
            return;
        }
        PDU response = event.getResponse();
        boolean securityReport = response != null && response.getType() == PDU.REPORT && response.size() > 0
                && isSecurityReportOid(response.get(0).getOid());
        if (response != null && !securityReport) {
            return;
        }
        MPv3 model = (MPv3) snmp.getMessageProcessingModel(MPv3.ID);
        synchronized (model) {
            synchronized (localizedKeys) {
                OctetString current = model.getEngineID(target.getAddress());
                if (current != null && current.equals(cached.attemptedEngine()) && localizedKeys.invalidate(cached)) {
                    // A silent replacement can discard REPORTs. The next poll must rediscover after a timeout too.
                    // Keep the USM time table intact; rediscovery must not bypass replay protection.
                    model.removeEngineID(target.getAddress());
                }
            }
        }
    }

    private <A extends Address> void cancelRequest(
            ResponseEvent<A> event,
            ResponseListener listener,
            SnmpEndpoint endpoint,
            String operation
    ) {
        try {
            snmp.cancel(event.getRequest(), listener);
        } catch (RuntimeException error) {
            logger.warn(
                    "Failed to cancel completed SNMP request device={} host={} port={} op={} version={} category=cleanup",
                    endpoint.deviceId(), endpoint.host(), endpoint.port(), operation,
                    endpoint.version(), error
            );
        }
    }

    private static <A extends Address> void completeGet(
            CompletableFuture<BatchResult> future,
            ResponseEvent<A> event,
            SnmpEndpoint endpoint,
            AbstractTarget<UdpAddress> target
    ) {
        if (event.getError() != null) {
            future.completeExceptionally(event.getError());
            return;
        }
        PDU response = event.getResponse();
        if (response == null) {
            future.completeExceptionally(new SnmpNoResponseException(
                    noResponseMessage(endpoint, "GET", target)
            ));
            return;
        }
        if (response.getType() == PDU.REPORT) {
            future.completeExceptionally(reportFailure(response));
            return;
        }
        if (response.getType() != PDU.RESPONSE) {
            future.completeExceptionally(unexpectedResponseType(response));
            return;
        }
        if (response.getErrorStatus() == PDU.noError && response.getErrorIndex() != 0) {
            future.completeExceptionally(new SnmpProtocolException(
                    PDU.genErr,
                    response.getErrorIndex(),
                    "SNMP GET response has error-status=noError but a non-zero error-index"
            ));
            return;
        }
        Instant receivedAt = Instant.now();
        List<RawBinding> bindings = new ArrayList<>(response.size());
        int bindingIndex = 0;
        for (VariableBinding binding : response.getVariableBindings()) {
            bindingIndex++;
            SnmpProtocolException malformed = malformedBinding("GET", bindingIndex, binding);
            if (malformed != null) {
                future.completeExceptionally(malformed);
                return;
            }
            bindings.add(toRawBinding(binding, receivedAt));
        }
        future.complete(new BatchResult(
                bindings,
                response.getErrorStatus(),
                response.getErrorIndex(),
                response.getErrorStatusText()
        ));
    }

    private static <A extends Address> void completeSet(
            CompletableFuture<SetResult> future,
            ResponseEvent<A> event,
            SnmpEndpoint endpoint,
            AbstractTarget<UdpAddress> target,
            NumericOid requestedOid
    ) {
        if (event.getError() != null) {
            future.completeExceptionally(event.getError());
            return;
        }
        PDU response = event.getResponse();
        if (response == null) {
            future.completeExceptionally(new SnmpNoResponseException(
                    noResponseMessage(endpoint, "SET", target)
            ));
            return;
        }
        if (response.getType() == PDU.REPORT) {
            future.completeExceptionally(reportFailure(response));
            return;
        }
        if (response.getType() != PDU.RESPONSE) {
            future.completeExceptionally(unexpectedResponseType(response));
            return;
        }
        if (response.getErrorStatus() == PDU.noError && response.getErrorIndex() != 0) {
            future.completeExceptionally(new SnmpProtocolException(
                    PDU.genErr,
                    response.getErrorIndex(),
                    "SNMP SET response has error-status=noError but a non-zero error-index"
            ));
            return;
        }
        if (response.getErrorStatus() != PDU.noError) {
            future.completeExceptionally(new SnmpProtocolException(
                    response.getErrorStatus(),
                    response.getErrorIndex(),
                    response.getErrorStatusText()
            ));
            return;
        }
        if (response.size() != 1) {
            future.completeExceptionally(new SnmpProtocolException(
                    PDU.genErr,
                    0,
                    "SNMP SET response must contain one variable binding"
            ));
            return;
        }
        VariableBinding responseBinding = response.get(0);
        SnmpProtocolException malformed = malformedBinding("SET", 1, responseBinding);
        if (malformed != null) {
            future.completeExceptionally(malformed);
            return;
        }
        OID actualOid = responseBinding.getOid();
        OID expectedOid = new OID(requestedOid.canonical());
        if (!expectedOid.equals(actualOid)) {
            future.completeExceptionally(new SnmpProtocolException(
                    PDU.genErr,
                    1,
                    "SNMP SET response OID mismatch expected=" + expectedOid.toDottedString()
                            + " actual=" + actualOid.toDottedString()
            ));
            return;
        }
        future.complete(new SetResult(toRawBinding(responseBinding, Instant.now())));
    }

    private PDU createPdu(SnmpEndpoint endpoint) {
        if (endpoint.version() != SNMPDeviceConfig.Version.V3) {
            return new PDU();
        }
        ScopedPDU pdu = new ScopedPDU();
        pdu.setContextName(new OctetString(endpoint.contextName()));
        return pdu;
    }

    private AbstractTarget<UdpAddress> createTarget(
            SnmpEndpoint endpoint,
            UdpAddress address,
            boolean write
    ) {
        AbstractTarget<UdpAddress> target;
        if (endpoint.version() == SNMPDeviceConfig.Version.V3) {
            AuthenticationProtocol auth = authenticationProtocol(endpoint);
            PrivacyProtocol privacy = privacyProtocol(endpoint);
            var userTarget = localizedKeys.target(endpoint, address, auth, privacy);
            userTarget.setVersion(SnmpConstants.version3);
            userTarget.setSecurityLevel(securityLevel(endpoint.securityLevel()));
            target = userTarget;
        } else {
            CommunityTarget<UdpAddress> communityTarget = new CommunityTarget<>();
            communityTarget.setCommunity(new OctetString(
                    write ? endpoint.writeCommunity() : endpoint.readCommunity()
            ));
            communityTarget.setVersion(endpoint.version() == SNMPDeviceConfig.Version.V1
                    ? SnmpConstants.version1
                    : SnmpConstants.version2c);
            communityTarget.setAddress(address);
            target = communityTarget;
        }
        target.setTimeout(endpoint.timeoutMillis());
        target.setRetries(endpoint.retries());
        target.setMaxSizeRequestPDU(endpoint.maxRequestBytes());
        target.setPreferredTransports(List.of(
                address.getInetAddress() instanceof Inet6Address
                        ? ipv6Transport
                        : ipv4Transport
        ));
        return target;
    }

    private AuthenticationProtocol authenticationProtocol(SnmpEndpoint endpoint) {
        if (endpoint.securityLevel() == SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV) {
            return null;
        }
        AuthenticationProtocol protocol = switch (endpoint.authProtocol()) {
            case MD5 -> securityProtocols.getAuthenticationProtocol(AuthMD5.ID);
            case SHA1 -> securityProtocols.getAuthenticationProtocol(AuthSHA.ID);
            case SHA224 -> securityProtocols.getAuthenticationProtocol(AuthHMAC128SHA224.ID);
            case SHA256 -> securityProtocols.getAuthenticationProtocol(AuthHMAC192SHA256.ID);
            case SHA384 -> securityProtocols.getAuthenticationProtocol(AuthHMAC256SHA384.ID);
            case SHA512 -> securityProtocols.getAuthenticationProtocol(AuthHMAC384SHA512.ID);
        };
        if (protocol == null) {
            throw new SecurityException(
                    "SNMPv3 authentication protocol is unavailable protocol=" + endpoint.authProtocol()
            );
        }
        return protocol;
    }

    private PrivacyProtocol privacyProtocol(SnmpEndpoint endpoint) {
        if (endpoint.securityLevel() != SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
            return null;
        }
        PrivacyProtocol protocol = switch (endpoint.privacyProtocol()) {
            case DES -> securityProtocols.getPrivacyProtocol(PrivDES.ID);
            case AES128 -> securityProtocols.getPrivacyProtocol(PrivAES128.ID);
            case AES192 -> securityProtocols.getPrivacyProtocol(PrivAES192.ID);
            case AES256 -> securityProtocols.getPrivacyProtocol(PrivAES256.ID);
        };
        if (protocol == null) {
            throw new SecurityException(
                    "SNMPv3 privacy protocol is unavailable protocol=" + endpoint.privacyProtocol()
            );
        }
        return protocol;
    }

    private static int securityLevel(SNMPDeviceConfig.SecurityLevel level) {
        return switch (level) {
            case NO_AUTH_NO_PRIV -> SecurityLevel.NOAUTH_NOPRIV;
            case AUTH_NO_PRIV -> SecurityLevel.AUTH_NOPRIV;
            case AUTH_PRIV -> SecurityLevel.AUTH_PRIV;
        };
    }

    private static String securityReportMessage(PDU response) {
        if (response.size() == 0) {
            return "SNMPv3 REPORT indicates a security failure reason=empty-report; verify security profile and agent logs";
        }
        VariableBinding reportBinding = response.get(0);
        OID reportOid = reportBinding == null ? null : reportBinding.getOid();
        if (reportOid == null) {
            return "SNMPv3 REPORT indicates a security failure reason=missing-report-oid; "
                    + "inspect the target agent and packet capture";
        }
        String reason;
        String action;
        if (reportOid.equals(SnmpConstants.usmStatsUnsupportedSecLevels)) {
            reason = "unsupported-security-level";
            action = "verify security level matches the SNMPv3 user";
        } else if (reportOid.equals(SnmpConstants.usmStatsNotInTimeWindows)) {
            reason = "not-in-time-window";
            action = "retry after engine-time resynchronization and verify agent engine state";
        } else if (reportOid.equals(SnmpConstants.usmStatsUnknownUserNames)) {
            reason = "unknown-user-name";
            action = "verify Security Name on the target agent";
        } else if (reportOid.equals(SnmpConstants.usmStatsUnknownEngineIDs)) {
            reason = "unknown-engine-id";
            action = "verify engine discovery is allowed and retry";
        } else if (reportOid.equals(SnmpConstants.usmStatsWrongDigests)) {
            reason = "wrong-auth-digest";
            action = "verify authentication protocol and passphrase";
        } else if (reportOid.equals(SnmpConstants.usmStatsDecryptionErrors)) {
            reason = "decryption-error";
            action = "verify privacy protocol and passphrase";
        } else {
            reason = "unrecognized-report";
            action = "inspect report OID and target agent logs";
        }
        return "SNMPv3 REPORT indicates a security failure reason=" + reason
                + " reportOid=" + reportOid.toDottedString()
                + "; " + action;
    }

    private static RuntimeException reportFailure(PDU response) {
        if (response.size() == 0) {
            return new SnmpReportException(
                    "message-processing",
                    StatusCodes.Bad_DecodingError,
                    true,
                    false,
                    "SNMPv3 REPORT is empty; inspect the target agent and packet capture"
            );
        }
        VariableBinding reportBinding = response.get(0);
        OID reportOid = reportBinding == null ? null : reportBinding.getOid();
        if (reportOid == null) {
            return new SnmpReportException(
                    "message-processing",
                    StatusCodes.Bad_DecodingError,
                    true,
                    false,
                    "SNMPv3 REPORT has no report OID; inspect the target agent and packet capture"
            );
        }
        if (isSecurityReportOid(reportOid)) {
            return new SnmpSecurityReportException(securityReportMessage(response));
        }
        if (reportOid.equals(SnmpConstants.snmpUnknownSecurityModels)) {
            return new SnmpSecurityReportException(
                    "SNMPv3 REPORT indicates a security failure reason=unknown-security-model"
                            + " reportOid=" + reportOid.toDottedString()
                            + "; verify the target supports SNMPv3 USM"
            );
        }
        if (reportOid.equals(SnmpConstants.snmpInvalidMsgs)) {
            return new SnmpReportException(
                    "message-processing",
                    StatusCodes.Bad_DecodingError,
                    true,
                    false,
                    reportMessage(reportOid, "invalid-message",
                            "verify SNMP version/security profile and inspect packet capture")
            );
        }
        if (reportOid.equals(SnmpConstants.snmpUnknownPDUHandlers)) {
            return new SnmpReportException(
                    "protocol",
                    StatusCodes.Bad_ServiceUnsupported,
                    false,
                    true,
                    reportMessage(reportOid, "unknown-pdu-handler",
                            "verify the target supports the requested SNMP operation")
            );
        }
        if (reportOid.equals(SnmpConstants.snmpUnavailableContexts)) {
            return new SnmpReportException(
                    "context",
                    StatusCodes.Bad_ResourceUnavailable,
                    true,
                    false,
                    reportMessage(reportOid, "unavailable-context",
                            "verify Context Name and target context availability")
            );
        }
        if (reportOid.equals(SnmpConstants.snmpUnknownContexts)) {
            return new SnmpReportException(
                    "context",
                    StatusCodes.Bad_ConfigurationError,
                    true,
                    false,
                    reportMessage(reportOid, "unknown-context",
                            "verify the configured Context Name")
            );
        }
        return new SnmpReportException(
                "report",
                StatusCodes.Bad_UnexpectedError,
                true,
                false,
                reportMessage(reportOid, "unrecognized-report",
                        "inspect the report OID, target agent logs, and packet capture")
        );
    }

    private static SnmpProtocolException unexpectedResponseType(PDU response) {
        return new SnmpProtocolException(
                PDU.genErr,
                0,
                "Unexpected SNMP response PDU type=" + PDU.getTypeString(response.getType())
                        + "(" + response.getType() + ")"
        );
    }

    private static SnmpProtocolException malformedBinding(
            String operation,
            int bindingIndex,
            VariableBinding binding
    ) {
        if (binding == null) {
            return new SnmpProtocolException(
                    PDU.genErr,
                    bindingIndex,
                    "SNMP " + operation + " response contains a null variable binding"
            );
        }
        if (binding.getOid() == null) {
            return new SnmpProtocolException(
                    PDU.genErr,
                    bindingIndex,
                    "SNMP " + operation + " response variable binding has no OID"
            );
        }
        if (binding.getVariable() == null) {
            return new SnmpProtocolException(
                    PDU.genErr,
                    bindingIndex,
                    "SNMP " + operation + " response variable binding has no value"
            );
        }
        return null;
    }

    private static boolean isSecurityReportOid(OID reportOid) {
        return reportOid.equals(SnmpConstants.usmStatsUnsupportedSecLevels)
                || reportOid.equals(SnmpConstants.usmStatsNotInTimeWindows)
                || reportOid.equals(SnmpConstants.usmStatsUnknownUserNames)
                || reportOid.equals(SnmpConstants.usmStatsUnknownEngineIDs)
                || reportOid.equals(SnmpConstants.usmStatsWrongDigests)
                || reportOid.equals(SnmpConstants.usmStatsDecryptionErrors);
    }

    private static String reportMessage(OID reportOid, String reason, String action) {
        return "SNMPv3 REPORT indicates failure reason=" + reason
                + " reportOid=" + reportOid.toDottedString()
                + "; " + action;
    }

    private static String noResponseMessage(
            SnmpEndpoint endpoint,
            String operation,
            AbstractTarget<UdpAddress> target
    ) {
        String protocol = switch (endpoint.version()) {
            case V1 -> "SNMPv1";
            case V2C -> "SNMPv2c";
            case V3 -> "SNMPv3";
        };
        String prefix = protocol + " " + operation
                + " received no response attempts=" + (endpoint.retries() + 1)
                + " timeoutPerAttemptMs=" + endpoint.timeoutMillis() + ". ";
        if (endpoint.version() != SNMPDeviceConfig.Version.V3) {
            String community = operation.equals("SET") ? "write community" : "read community";
            return prefix + "Verify device reachability, UDP port/ACL, SNMP version, and " + community + ".";
        }

        boolean engineDiscovered = target instanceof UserTarget<?> userTarget
                && userTarget.getAuthoritativeEngineID() != null
                && userTarget.getAuthoritativeEngineID().length > 0;
        String discovery = engineDiscovered
                ? "SNMPv3 engine discovery completed, but the secured request was unanswered. "
                : "No usable SNMPv3 response was received during discovery or secured request. ";
        String securityChecks = endpoint.securityLevel() == SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV
                ? "Verify Security Name, security level, context, and agent access policy."
                : "Verify Security Name, security level, auth protocol/passphrase"
                        + (endpoint.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV
                        ? ", privacy protocol/passphrase" : "")
                        + ", context, and agent access policy. An incorrect auth/privacy secret may be silently discarded by the agent and cannot be distinguished from packet loss without a trusted REPORT.";
        return prefix + discovery
                + "Verify device reachability and UDP port/ACL first. "
                + securityChecks;
    }

    private static RawBinding toRawBinding(VariableBinding binding, Instant receivedAt) {
        return new RawBinding(
                NumericOid.parse(binding.getOid().toDottedString()),
                binding.getVariable().getSyntax(),
                binding.getVariable(),
                receivedAt
        );
    }

    @Override
    public synchronized void close() {
        started.set(false);
        localizedKeys.close();
        if (!closed) {
            closed = true;
            IOException failure = new IOException("Failed to close shared SNMP transport");
            closeResources(snmp, ipv4Transport, ipv6Transport, failure);
            if (failure.getSuppressed().length != 0) {
                logger.warn(failure.getMessage(), failure);
            }
        }
    }
}

