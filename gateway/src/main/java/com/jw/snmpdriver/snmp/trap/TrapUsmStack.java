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
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;

/**
 * One Listener's private SecurityProtocols, USM and MPv3, never shared with the polling stack or the SNMP4J
 * globals. Users are localized to the configured sender engineID when registered (ADR 0003): there is no
 * wildcard user and engine discovery is off, so a spoofed engineID is rejected as unknown and grows neither
 * the user table nor the time table.
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

    public TrapUsmStack(byte[] localEngineId, int engineBoots) {
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
                int status = super.processIncomingMsg(snmpVersion, maxMessageSize, parameters, model, securityLevel,
                        wholeMsg, transport, securityEngineID, securityName, scopedPDU, maxSizeResponseScopedPDU,
                        securityStateReference, statusInfo);
                if (status == SnmpConstants.SNMPv3_USM_OK) {
                    incomingEngine.set(securityEngineID.getValue());
                }
                return status;
            }
        };
        usm.setEngineDiscoveryEnabled(false);
        mpv3 = new MPv3(localEngineId, null, protocols, new SecurityModels().addSecurityModel(usm),
                CounterSupport.getInstance());
    }

    /** The engineID USM authenticated the current transport thread's message with; null when none. */
    public byte[] takeIncomingEngineId() {
        byte[] engine = incomingEngine.get();
        incomingEngine.remove();
        return engine;
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
     * Localizes the user to its sender engineID and lets that engine past the time table. Devices sharing the
     * same securityName and engine share the entry; mismatched same-name users never get here (#37).
     */
    public synchronized void register(TrapV3User user) {
        if (!user.receivesTraps()) {
            return;
        }
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
            usm.removeEngineTime(key.engine());
        }
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
