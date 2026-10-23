package com.jw.snmpdriver.snmp.trap;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.snmp4j.TransportStateReference;
import org.snmp4j.asn1.BERInputStream;
import org.snmp4j.asn1.BEROutputStream;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityParameters;
import org.snmp4j.security.SecurityStateReference;
import org.snmp4j.smi.Integer32;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.AuthHMAC128SHA224;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC256SHA384;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmTimeEntry;
import org.snmp4j.security.UsmSecurityParameters;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;

/**
 * One Listener's private SecurityProtocols, USM and MPv3, never shared with the polling stack or the SNMP4J
 * globals. Users are localized to the configured sender engineID when registered (ADR 0003): there is no
 * wildcard user and engine discovery is off, so a spoofed engineID is rejected as unknown and grows neither
 * the user table nor the time table.
 * <p>
 * With {@code informEnabled} the Gateway is also the authoritative engine of v3 Informs (RFC 3414): every user
 * is localized to the local engineID as well, and the local engine is the one entry the time table knows
 * without a registration, so a sender's discovery and time sync are answered from the local boots/time.
 */
public final class TrapUsmStack {
    private final SecurityProtocols protocols;
    private final USM usm;
    private final MPv3 mpv3;
    /**
     * The authoritative engine of the message USM just accepted. MPv3 drops the state reference of a Trap, and
     * the dispatcher hands the PDU to the responder on this same transport thread, so it is read there.
     */
    private final ThreadLocal<byte[]> incomingEngine = new ThreadLocal<>();
    private final Map<Key, Integer> users = new HashMap<>();
    private final Map<OctetString, Integer> engines = new HashMap<>();
    private final OctetString localEngine;
    private final boolean informEnabled;
    private final Map<OctetString, Integer> informUsers = new HashMap<>();
    /** How USM's last rejection on this transport thread is to be reported; read by the failure listener. */
    private final ThreadLocal<Rejection> rejection = new ThreadLocal<>();

    /** What a USM rejection was, for counting it. */
    public enum Rejection {
        /** Any other message: counted as an authentication failure. */
        OTHER,
        /** A message claiming the Gateway engine, i.e. a v3 Inform: counted with the Inform reason. */
        LOCAL_ENGINE,
        /** A sender's engine discovery or time sync (RFC 3414 §4): answered by a Report, never counted. */
        DISCOVERY
    }

    /** A Trap-only stack: no user is localized to the local engine, so no v3 Inform authenticates. */
    public TrapUsmStack(byte[] localEngineId, int engineBoots) {
        this(localEngineId, engineBoots, false);
    }

    public TrapUsmStack(byte[] localEngineId, int engineBoots, boolean informEnabled) {
        this(localEngineId, engineBoots, 0, informEnabled);
    }

    /**
     * {@code engineTime} is the local engine's current time in seconds, from the registry-wide clock: a new USM
     * would otherwise restart it at 0 and move the same authoritative engine back in time.
     */
    public TrapUsmStack(byte[] localEngineId, int engineBoots, int engineTime, boolean informEnabled) {
        localEngine = new OctetString(localEngineId);
        this.informEnabled = informEnabled;
        protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthMD5());
        protocols.addAuthenticationProtocol(new AuthSHA());
        protocols.addAuthenticationProtocol(new AuthHMAC128SHA224());
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addAuthenticationProtocol(new AuthHMAC256SHA384());
        protocols.addAuthenticationProtocol(new AuthHMAC384SHA512());
        protocols.addPrivacyProtocol(new PrivDES());
        protocols.addPrivacyProtocol(new PrivAES128());
        protocols.addPrivacyProtocol(new PrivAES192());
        protocols.addPrivacyProtocol(new PrivAES256());
        usm = new USM(protocols, new OctetString(localEngineId), engineBoots) {
            @Override
            public int processIncomingMsg(int snmpVersion, int maxMessageSize, SecurityParameters parameters,
                                          SecurityModel model, int securityLevel, BERInputStream wholeMsg,
                                          TransportStateReference transport, OctetString securityEngineID,
                                          OctetString securityName, BEROutputStream scopedPDU,
                                          Integer32 maxSizeResponseScopedPDU,
                                          SecurityStateReference securityStateReference,
                                          StatusInformation statusInfo) throws IOException {
                incomingEngine.remove();
                rejection.remove();
                int status = super.processIncomingMsg(snmpVersion, maxMessageSize, parameters, model, securityLevel,
                        wholeMsg, transport, securityEngineID, securityName, scopedPDU, maxSizeResponseScopedPDU,
                        securityStateReference, statusInfo);
                if (status == SnmpConstants.SNMPv3_USM_OK) {
                    incomingEngine.set(securityEngineID.getValue());
                } else {
                    rejection.set(classify(securityEngineID, (UsmSecurityParameters) parameters));
                }
                return status;
            }
        };
        // discovery stays off: only the local engine (below) and registered sender engines pass checkEngineID
        usm.setEngineDiscoveryEnabled(false);
        usm.getTimeTable().setLocalTime(new UsmTimeEntry(localEngine, engineBoots, engineTime));
        if (informEnabled) {
            // the time is checked against the local clock (authoritative branch), this entry only admits the id
            usm.getTimeTable().addEntry(new UsmTimeEntry(localEngine, engineBoots, 0));
        }
        mpv3 = new MPv3(localEngineId, null, protocols, new SecurityModels().addSecurityModel(usm),
                CounterSupport.getInstance());
    }

    /** The engineID USM authenticated the current transport thread's message with; null when none. */
    public byte[] takeIncomingEngineId() {
        byte[] engine = incomingEngine.get();
        incomingEngine.remove();
        return engine;
    }

    /** How to report the message USM rejected on this transport thread; clears the record. */
    public Rejection takeRejection() {
        Rejection value = rejection.get();
        rejection.remove();
        return value == null ? Rejection.OTHER : value;
    }

    /**
     * An empty engineID is a discovery probe; boots and time 0 toward the local engine is the sender's first
     * time sync. Both get a Report with the local values and are not failures.
     */
    private Rejection classify(OctetString engine, UsmSecurityParameters parameters) {
        if (engine.length() == 0) {
            return Rejection.DISCOVERY;
        }
        if (!informEnabled || !localEngine.equals(engine)) {
            return Rejection.OTHER;
        }
        // at the boots limit no sync can succeed (RFC 3414 §2.2.2), so the sync itself is the refused Inform
        boolean sync = parameters.getAuthoritativeEngineBoots() == 0 && parameters.getAuthoritativeEngineTime() == 0;
        return sync && usm.getEngineBoots() != Integer.MAX_VALUE ? Rejection.DISCOVERY : Rejection.LOCAL_ENGINE;
    }

    /** The engine v3 Informs must be addressed to, as {@code Traps/LocalEngineId} shows it. */
    public byte[] localEngineId() {
        return localEngine.getValue();
    }

    public boolean informEnabled() {
        return informEnabled;
    }

    public SecurityProtocols securityProtocols() {
        return protocols;
    }

    public USM usm() {
        return usm;
    }

    public MPv3 mpv3() {
        return mpv3;
    }

    /**
     * Localizes the user to its sender engineID and lets that engine past the time table, and with Inform
     * enabled also to the local engineID. Devices sharing the same securityName and engine share the entry;
     * mismatched same-name users never get here (#37).
     */
    public synchronized void register(TrapV3User user) {
        // the Trap side may reject the engineID: count the Inform side only after it succeeded
        if (user.receivesTraps()) {
            registerTrap(user);
        }
        registerInform(user);
    }

    private void registerTrap(TrapV3User user) {
        Key key = Key.of(user);
        Integer count = users.get(key);
        if (count == null) {
            usm.addUser(new UsmUser(key.name(), user.authProtocol(), octets(user.authPassphrase()),
                    user.privacyProtocol(), octets(user.privacyPassphrase())), key.engine());
            if (engines.merge(key.engine(), 1, Integer::sum) == 1
                    && usm.getTimeTable().getEntry(key.engine()) == null) {
                // the sender is authoritative: its first Trap sets boots/time, later ones must not go back
                usm.getTimeTable().addEntry(new UsmTimeEntry(key.engine(), 0, 0));
            }
        }
        users.put(key, count == null ? 1 : count + 1);
    }

    public synchronized void unregister(TrapV3User user) {
        unregisterInform(user);
        if (!user.receivesTraps()) {
            return;
        }
        Key key = Key.of(user);
        Integer count = users.get(key);
        if (count == null) {
            return;
        }
        if (count > 1) {
            users.put(key, count - 1);
            return;
        }
        users.remove(key);
        usm.removeAllUsers(key.name(), key.engine());
        if (engines.merge(key.engine(), -1, Integer::sum) <= 0) {
            engines.remove(key.engine());
            if (!informEnabled || !localEngine.equals(key.engine())) {
                usm.removeEngineTime(key.engine());
            }
        }
    }

    /** v3 Inform: the same user localized to the Gateway engine, independent of trapSenderEngineId. */
    private void registerInform(TrapV3User user) {
        if (!informEnabled) {
            return;
        }
        OctetString name = new OctetString(user.securityName());
        if (informUsers.merge(name, 1, Integer::sum) == 1) {
            usm.addUser(new UsmUser(name, user.authProtocol(), octets(user.authPassphrase()),
                    user.privacyProtocol(), octets(user.privacyPassphrase())), localEngine);
        }
    }

    private void unregisterInform(TrapV3User user) {
        if (!informEnabled) {
            return;
        }
        OctetString name = new OctetString(user.securityName());
        Integer count = informUsers.get(name);
        if (count == null) {
            return;
        }
        if (count > 1) {
            informUsers.put(name, count - 1);
            return;
        }
        informUsers.remove(name);
        usm.removeAllUsers(name, localEngine);
    }

    public int userCount() {
        return usm.getUserTable().getUserEntries().size();
    }

    /** Whether the time table tracks this remote engine; only registered sender engines ever do. */
    public boolean tracksEngine(byte[] engineId) {
        return usm.getTimeTable().getEntry(new OctetString(engineId)) != null;
    }

    private static OctetString octets(byte[] value) {
        return value == null ? null : new OctetString(value);
    }

    private record Key(OctetString name, OctetString engine) {
        static Key of(TrapV3User user) {
            return new Key(new OctetString(user.securityName()), new OctetString(user.senderEngineId()));
        }
    }
}
