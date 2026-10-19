package com.jw.snmpdriver.snmp.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.snmp4j.DirectUserTarget;
import org.snmp4j.Target;
import org.snmp4j.security.AuthenticationProtocol;
import org.snmp4j.security.PrivacyProtocol;
import org.snmp4j.security.SecretOctetString;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

/** Client-owned, bounded key material; an endpoint's object identity is its configuration generation. */
final class LocalizedKeyCache {
    private static final int CAPACITY = 1_024;
    private final Map<Key, Keys> entries = new LinkedHashMap<>(16, 0.75f, true);
    // Weak generation metadata tracks live devices without retaining retired endpoint credentials.
    private final Map<String, GenerationReference> generations = new LinkedHashMap<>();
    private final ReferenceQueue<SnmpEndpoint> retiredGenerations = new ReferenceQueue<>();
    private boolean closed;

    synchronized void activate(SnmpEndpoint endpoint) {
        if (closed) {
            return;
        }
        GenerationReference retired;
        while ((retired = (GenerationReference) retiredGenerations.poll()) != null) {
            generations.remove(retired.deviceId, retired);
        }
        GenerationReference previous = generations.get(endpoint.deviceId());
        if (previous == null || previous.get() != endpoint) {
            generations.put(endpoint.deviceId(), new GenerationReference(endpoint, retiredGenerations));
            entries.keySet().removeIf(key -> key.endpoint.deviceId().equals(endpoint.deviceId()));
        }
    }

    synchronized boolean isCurrent(SnmpEndpoint endpoint) {
        GenerationReference current = generations.get(endpoint.deviceId());
        return !closed && current != null && current.get() == endpoint;
    }

    synchronized boolean invalidate(CachedTarget target) {
        if (!isCurrent(target.endpoint)) {
            return false;
        }
        entries.keySet().removeIf(key -> key.endpoint == target.endpoint);
        return true;
    }

    CachedTarget target(SnmpEndpoint endpoint, UdpAddress address,
                        AuthenticationProtocol auth, PrivacyProtocol privacy) {
        return new CachedTarget(endpoint, address, auth, privacy);
    }

    private synchronized Keys keys(SnmpEndpoint endpoint, byte[] engineId,
                                   AuthenticationProtocol auth, PrivacyProtocol privacy) {
        Key key = new Key(endpoint, new OctetString(engineId),
                auth == null ? null : auth.getID(), privacy == null ? null : privacy.getID());
        Keys result = entries.get(key);
        if (result == null) {
            result = new Keys(auth == null ? null : new SecretOctetString(auth.passwordToKey(
                    new SecretOctetString(new OctetString(endpoint.authPassphrase())), engineId)),
                    auth == null || privacy == null ? null : new SecretOctetString(SecurityProtocols.passwordToKey(
                            privacy, auth, new SecretOctetString(new OctetString(endpoint.privacyPassphrase())), engineId)));
            if (isCurrent(endpoint)) {
                entries.put(key, result);
                if (entries.size() > CAPACITY) {
                    entries.remove(entries.keySet().iterator().next());
                }
            }
        }
        return result;
    }

    synchronized void close() {
        closed = true;
        entries.clear();
        generations.clear();
    }

    private record Keys(SecretOctetString auth, SecretOctetString privacy) {}

    private static final class GenerationReference extends WeakReference<SnmpEndpoint> {
        private final String deviceId;

        GenerationReference(SnmpEndpoint endpoint, ReferenceQueue<SnmpEndpoint> queue) {
            super(endpoint, queue);
            deviceId = endpoint.deviceId();
        }
    }

    private record Key(SnmpEndpoint endpoint, OctetString engineId, OID auth, OID privacy) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && endpoint == key.endpoint
                    && engineId.equals(key.engineId) && Objects.equals(auth, key.auth)
                    && Objects.equals(privacy, key.privacy);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(endpoint), engineId, auth, privacy);
        }
    }

    final class CachedTarget extends DirectUserTarget<UdpAddress> {
        private final SnmpEndpoint endpoint;
        private final Attempt attempt;

        private CachedTarget(SnmpEndpoint endpoint, UdpAddress address,
                             AuthenticationProtocol auth, PrivacyProtocol privacy) {
            this(endpoint, address, auth, privacy, new Attempt());
        }

        private CachedTarget(SnmpEndpoint endpoint, UdpAddress address,
                             AuthenticationProtocol auth, PrivacyProtocol privacy, Attempt attempt) {
            this.endpoint = endpoint;
            this.attempt = attempt;
            setAddress(address);
            setSecurityName(new OctetString(endpoint.securityName()));
            setAuthenticationProtocol(auth);
            setPrivacyProtocol(privacy);
        }

        @Override
        public synchronized void setAuthoritativeEngineID(byte[] engineId) {
            authoritativeEngineID = new OctetString(engineId);
            if (engineId.length == 0) {
                setAuthenticationKey(null);
                setPrivacyKey(null);
            } else {
                Keys keys = keys(endpoint, engineId, getAuthenticationProtocol(), getPrivacyProtocol());
                // Targets and the cache never share mutable OctetString instances.
                setAuthenticationKey(keys.auth == null ? null : new SecretOctetString(keys.auth));
                setPrivacyKey(keys.privacy == null ? null : new SecretOctetString(keys.privacy));
                attempt.engineId = new OctetString(engineId);
            }
        }

        OctetString attemptedEngine() {
            return attempt.engineId;
        }

        SnmpEndpoint endpoint() {
            return endpoint;
        }

        @Override
        public synchronized Target<UdpAddress> duplicate() {
            CachedTarget copy = new CachedTarget(endpoint, getAddress(), getAuthenticationProtocol(), getPrivacyProtocol(), attempt);
            copy.setSecurityLevel(getSecurityLevel());
            copy.setRetries(getRetries());
            copy.setTimeout(getTimeout());
            copy.setMaxSizeRequestPDU(getMaxSizeRequestPDU());
            copy.setSecurityModel(getSecurityModel());
            copy.setVersion(getVersion());
            copy.setPreferredTransports(getPreferredTransports());
            copy.setAuthoritativeEngineID(getAuthoritativeEngineID());
            return copy;
        }
    }

    private static final class Attempt {
        private volatile OctetString engineId = new OctetString();
    }
}
