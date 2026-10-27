package com.jw.snmpdriver.snmp.trap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
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
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.OID;

/**
 * A Device's USM user on the Trap stack. {@code senderEngineId} is the v3 Trap sender's authoritative engine
 * (ADR 0003); null means this Device receives no v3 Trap. Protocols and passphrases are null below the level
 * that needs them. {@code credentialRef} identifies the referenced SecretConfigs the passphrases came from; null
 * when they are embedded or absent, so only their plaintext is compared (#37).
 */
public record TrapV3User(
        String securityName,
        int securityLevel,
        OID authProtocol,
        byte[] authPassphrase,
        OID privacyProtocol,
        byte[] privacyPassphrase,
        byte[] senderEngineId,
        String credentialRef
) {
    public TrapV3User(String securityName, int securityLevel, OID authProtocol, byte[] authPassphrase,
                      OID privacyProtocol, byte[] privacyPassphrase, byte[] senderEngineId) {
        this(securityName, securityLevel, authProtocol, authPassphrase, privacyProtocol, privacyPassphrase,
                senderEngineId, null);
    }

    /** Traps/LastError while a USM Device has no sender engineID (ADR 0003). */
    public static final String NO_SENDER_ENGINE_ID = "未配置 Trap Sender Engine ID，不接收 v3 Trap。请在设备上读取 "
            + "snmpEngineID.0（1.3.6.1.6.3.10.2.1.1.0）并填入 Trap Sender Engine ID。";

    public static TrapV3User of(String securityName, SNMPDeviceConfig.SecurityLevel level,
                                SNMPDeviceConfig.AuthProtocol auth, String authPassphrase,
                                SNMPDeviceConfig.PrivacyProtocol privacy, String privacyPassphrase,
                                String senderEngineHex) {
        return of(securityName, level, auth, authPassphrase, privacy, privacyPassphrase, senderEngineHex, null);
    }

    public static TrapV3User of(String securityName, SNMPDeviceConfig.SecurityLevel level,
                                SNMPDeviceConfig.AuthProtocol auth, String authPassphrase,
                                SNMPDeviceConfig.PrivacyProtocol privacy, String privacyPassphrase,
                                String senderEngineHex, String credentialRef) {
        int snmpLevel = switch (level) {
            case NO_AUTH_NO_PRIV -> SecurityLevel.NOAUTH_NOPRIV;
            case AUTH_NO_PRIV -> SecurityLevel.AUTH_NOPRIV;
            case AUTH_PRIV -> SecurityLevel.AUTH_PRIV;
        };
        boolean authenticated = snmpLevel >= SecurityLevel.AUTH_NOPRIV;
        boolean encrypted = snmpLevel == SecurityLevel.AUTH_PRIV;
        return new TrapV3User(securityName == null ? "" : securityName, snmpLevel,
                authenticated ? authOid(auth) : null, authenticated ? bytes(authPassphrase) : null,
                encrypted ? privOid(privacy) : null, encrypted ? bytes(privacyPassphrase) : null,
                senderEngineHex == null || senderEngineHex.isBlank()
                        ? null : HexFormat.of().parseHex(SNMPDeviceConfig.normalizeEngineId(senderEngineHex)),
                credentialRef);
    }

    /**
     * Whether a same-named user on the same Listener could share this one's USM entry: same SecretConfig
     * references, level, protocols and plaintext. The sender engine is not compared.
     */
    public boolean sameCredentials(TrapV3User other) {
        return securityLevel == other.securityLevel
                && Objects.equals(credentialRef, other.credentialRef)
                && Objects.equals(authProtocol, other.authProtocol)
                && Arrays.equals(authPassphrase, other.authPassphrase)
                && Objects.equals(privacyProtocol, other.privacyProtocol)
                && Arrays.equals(privacyPassphrase, other.privacyPassphrase);
    }

    public boolean receivesTraps() {
        return senderEngineId != null;
    }

    private static byte[] bytes(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    private static OID authOid(SNMPDeviceConfig.AuthProtocol protocol) {
        return switch (protocol) {
            case MD5 -> AuthMD5.ID;
            case SHA1 -> AuthSHA.ID;
            case SHA224 -> AuthHMAC128SHA224.ID;
            case SHA256 -> AuthHMAC192SHA256.ID;
            case SHA384 -> AuthHMAC256SHA384.ID;
            case SHA512 -> AuthHMAC384SHA512.ID;
        };
    }

    private static OID privOid(SNMPDeviceConfig.PrivacyProtocol protocol) {
        return switch (protocol) {
            case DES -> PrivDES.ID;
            case AES128 -> PrivAES128.ID;
            case AES192 -> PrivAES192.ID;
            case AES256 -> PrivAES256.ID;
        };
    }
}
