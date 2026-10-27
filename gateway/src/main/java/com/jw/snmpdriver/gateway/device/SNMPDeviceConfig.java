package com.jw.snmpdriver.gateway.device;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.IsNullable;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

public record SNMPDeviceConfig(
        @FormCategory("CONNECTION") @Label("Host*") @FormField(FormFieldType.TEXT)
        @DefaultValue("") @Required String host,
        @FormCategory("CONNECTION") @Label("Port*") @FormField(FormFieldType.NUMBER)
        @DefaultValue("161") @Required int port,
        @FormCategory("CONNECTION") @Label("IP Family*") @FormField(FormFieldType.SELECT)
        @DefaultValue("AUTO") @Required IpFamily ipFamily,
        @FormCategory("PROTOCOL") @Label("SNMP Version*") @FormField(FormFieldType.SELECT)
        @DefaultValue("V3") @Required Version version,
        @FormCategory("PROTOCOL") @Label("Read Community") @FormField(FormFieldType.SECRET)
        @DefaultValue("null") @IsNullable SecretConfig readCommunity,
        @FormCategory("PROTOCOL") @Label("Write Community") @FormField(FormFieldType.SECRET)
        @DefaultValue("null") @IsNullable SecretConfig writeCommunity,
        @FormCategory("V3_SECURITY") @Label("Security Name") @FormField(FormFieldType.TEXT)
        @DefaultValue("") String securityName,
        @FormCategory("V3_SECURITY") @Label("Security Level*") @FormField(FormFieldType.SELECT)
        @DefaultValue("AUTH_PRIV") @Required SecurityLevel securityLevel,
        @FormCategory("V3_SECURITY") @Label("Auth Protocol*") @FormField(FormFieldType.SELECT)
        @DefaultValue("SHA256") @Required AuthProtocol authProtocol,
        @FormCategory("V3_SECURITY") @Label("Auth Passphrase") @FormField(FormFieldType.SECRET)
        @DefaultValue("null") @IsNullable SecretConfig authPassphrase,
        @FormCategory("V3_SECURITY") @Label("Privacy Protocol*") @FormField(FormFieldType.SELECT)
        @DefaultValue("AES128") @Required PrivacyProtocol privacyProtocol,
        @FormCategory("V3_SECURITY") @Label("Privacy Passphrase") @FormField(FormFieldType.SECRET)
        @DefaultValue("null") @IsNullable SecretConfig privacyPassphrase,
        @FormCategory("V3_SECURITY") @Label("Context Name") @FormField(FormFieldType.TEXT)
        @DefaultValue("") String contextName,
        @FormCategory("ACCESS") @Label("Access Mode*") @FormField(FormFieldType.SELECT)
        @DefaultValue("READ_ONLY") @Required AccessMode accessMode,
        @FormCategory("RUNTIME") @Label("Timeout (ms)*") @FormField(FormFieldType.NUMBER)
        @DefaultValue("2000") @Required long timeoutMillis,
        @FormCategory("RUNTIME") @Label("Retries*") @FormField(FormFieldType.NUMBER)
        @DefaultValue("1") @Required int retries,
        @FormCategory("RUNTIME") @Label("Max OIDs per Request*") @FormField(FormFieldType.NUMBER)
        @DefaultValue("25") @Required int maxOidsPerRequest,
        @FormCategory("RUNTIME") @Label("Max Request Bytes*") @FormField(FormFieldType.NUMBER)
        @DefaultValue("1200") @Required int maxRequestBytes,
        @FormCategory("RUNTIME") @Label("Max In-flight Requests*") @FormField(FormFieldType.NUMBER)
        @DefaultValue("2") @Required int maxInflightPerDevice,
        @FormCategory("TRAP") @Label("Trap Enabled (SNMP Trap/Inform receiver)") @FormField(FormFieldType.CHECKBOX)
        @DefaultValue("false") @Required boolean trapEnabled,
        @FormCategory("TRAP") @Label("Trap Bind Address* (IP literal; first device on a port decides the listener)")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("0.0.0.0") @Required String trapBindAddress,
        @FormCategory("TRAP") @Label("Trap Port* (Linux: use 10162 and redirect UDP 162 with nftables)")
        @FormField(FormFieldType.NUMBER)
        @DefaultValue("162") @Required int trapPort,
        @FormCategory("TRAP") @Label("Trap Security* (USM requires SNMP Version V3)") @FormField(FormFieldType.SELECT)
        @DefaultValue("COMMUNITY") @Required TrapSecurity trapSecurity,
        @FormCategory("TRAP") @Label("Trap Community (COMMUNITY mode; empty uses Read Community)")
        @FormField(FormFieldType.SECRET)
        @DefaultValue("null") @IsNullable SecretConfig trapCommunity,
        @FormCategory("TRAP") @Label("Trap Sender Engine ID (V3 only; hex, 5-32 bytes; required to receive V3 Traps)")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("") String trapSenderEngineId,
        @FormCategory("TRAP") @Label("Accept Inform") @FormField(FormFieldType.CHECKBOX)
        @DefaultValue("true") @Required boolean trapAcceptInform,
        @FormCategory("TRAP") @Label("Trap Source Allowlist (comma-separated IP or CIDR)")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("") String trapSourceAllowlist,
        @FormCategory("TRAP") @Label("Trap Rate Limit per Second* (1-10000)") @FormField(FormFieldType.NUMBER)
        @DefaultValue("100") @Required int trapRateLimitPerSecond,
        @FormCategory("TRAP") @Label("Record Handler Project (project whose Script Library holds the function)")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("") String recordHandlerProject,
        @FormCategory("TRAP")
        @Label("Record Handler Function (e.g. snmp.handlers.onNotification; called once per accepted Notification; empty disables)")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("") String recordHandlerFunction,
        @FormCategory("TRAP")
        @Label("Record Queue Capacity* (100-100000; Records waiting for the function; when full, new Records are dropped)")
        @FormField(FormFieldType.NUMBER)
        @DefaultValue("10000") @Required int recordQueueCapacity
) {
    public static final int MIN_RECORD_QUEUE_CAPACITY = 100;
    public static final int MAX_RECORD_QUEUE_CAPACITY = 100_000;

    /** Dotted Script Library path: at least "module.function", each part a Python identifier. */
    private static final java.util.regex.Pattern RECORD_HANDLER_FUNCTION =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    public enum Version { V1, V2C, V3 }
    public enum IpFamily { AUTO, IPV4, IPV6 }
    public enum SecurityLevel { NO_AUTH_NO_PRIV, AUTH_NO_PRIV, AUTH_PRIV }
    public enum AuthProtocol { MD5, SHA1, SHA224, SHA256, SHA384, SHA512 }
    public enum PrivacyProtocol { DES, AES128, AES192, AES256 }
    public enum AccessMode { READ_ONLY, READ_WRITE }
    public enum TrapSecurity { COMMUNITY, USM }

    public static SNMPDeviceConfig defaults() {
        return new SNMPDeviceConfig(
                "", 161, IpFamily.AUTO, Version.V3, null, null, "",
                SecurityLevel.AUTH_PRIV, AuthProtocol.SHA256, null,
                PrivacyProtocol.AES128, null, "", AccessMode.READ_ONLY,
                2_000L, 1, 25, 1_200, 2,
                false, "0.0.0.0", 162, TrapSecurity.COMMUNITY, null, "", true, "", 100,
                "", "", 10_000
        );
    }

    /** True for a dotted function path such as {@code snmp.handlers.onNotification}; surrounding blanks allowed. */
    public static boolean isValidRecordHandlerFunction(String function) {
        return function != null && RECORD_HANDLER_FUNCTION.matcher(function.strip()).matches();
    }

    /**
     * An engineID as tools print it ({@code 80 00 1F 88 …}, {@code 80:00:1f:…}, {@code 0x80001F…}) reduced to
     * plain hex digits; empty for null. Whether the result is valid hex is left to the caller.
     */
    public static String normalizeEngineId(String engineId) {
        if (engineId == null) {
            return "";
        }
        String hex = engineId.replaceAll("[\\s:]", "");
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }
}

