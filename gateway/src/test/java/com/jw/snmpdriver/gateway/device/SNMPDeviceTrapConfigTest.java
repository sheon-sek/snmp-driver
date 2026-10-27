package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.jw.snmpdriver.gateway.service.ServiceBundle;
import org.junit.jupiter.api.Test;

class SNMPDeviceTrapConfigTest {
    private static final String V1_0_0_JSON = """
            {"host":"10.0.0.8","port":1161,"ipFamily":"IPV4","version":"V3",
             "securityName":"monitor","securityLevel":"NO_AUTH_NO_PRIV",
             "authProtocol":"SHA256","privacyProtocol":"AES128","contextName":"ctx",
             "accessMode":"READ_ONLY","timeoutMillis":5000,"retries":3,
             "maxOidsPerRequest":10,"maxRequestBytes":900,"maxInflightPerDevice":4}
            """;

    private final SNMPDeviceExtensionPoint extensionPoint =
            new SNMPDeviceExtensionPoint(new ServiceBundle());

    @Test
    void decodesVersion100JsonWithTrapDefaultsAndUnchangedPollingFields() throws Exception {
        SNMPDeviceConfig config = extensionPoint.decode(JsonParser.parseString(V1_0_0_JSON));

        assertEquals("10.0.0.8", config.host());
        assertEquals(1161, config.port());
        assertEquals(SNMPDeviceConfig.IpFamily.IPV4, config.ipFamily());
        assertEquals(SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, config.securityLevel());
        assertEquals("ctx", config.contextName());
        assertEquals(5000L, config.timeoutMillis());
        assertEquals(3, config.retries());
        assertEquals(10, config.maxOidsPerRequest());
        assertEquals(900, config.maxRequestBytes());
        assertEquals(4, config.maxInflightPerDevice());

        assertFalse(config.trapEnabled());
        assertEquals("0.0.0.0", config.trapBindAddress());
        assertEquals(162, config.trapPort());
        assertEquals(SNMPDeviceConfig.TrapSecurity.COMMUNITY, config.trapSecurity());
        assertNull(config.trapCommunity());
        assertEquals("", config.trapSenderEngineId());
        assertTrue(config.trapAcceptInform());
        assertEquals("", config.trapSourceAllowlist());
        assertEquals(100, config.trapRateLimitPerSecond());
        assertEquals("", config.recordHandlerProject());
        assertEquals("", config.recordHandlerFunction());
        assertEquals(10_000, config.recordQueueCapacity());
    }

    @Test
    void recordQueueCapacityDefaultsToTenThousand() {
        assertEquals(10_000, SNMPDeviceConfig.defaults().recordQueueCapacity());
    }

    @Test
    void rejectsRecordQueueCapacityOutsideOneHundredToOneHundredThousand() throws Exception {
        for (int capacity : new int[] {0, 99, -1, 100_001}) {
            assertFieldError("recordQueueCapacity", decode(Map.of("recordQueueCapacity", capacity)));
        }
        for (int capacity : new int[] {100, 10_000, 100_000}) {
            SNMPDeviceConfig config = decode(Map.of("recordQueueCapacity", capacity));
            assertEquals(capacity, config.recordQueueCapacity());
            assertEquals(Map.of(), fieldErrors(config), String.valueOf(capacity));
        }
    }

    @Test
    void recordHandlerFieldsDefaultToEmpty() {
        assertEquals("", SNMPDeviceConfig.defaults().recordHandlerProject());
        assertEquals("", SNMPDeviceConfig.defaults().recordHandlerFunction());
    }

    @Test
    void explicitRecordHandlerValuesAreKept() throws Exception {
        SNMPDeviceConfig config = decode(Map.of(
                "recordHandlerProject", "Plant",
                "recordHandlerFunction", "snmp.handlers.onNotification"));

        assertEquals("Plant", config.recordHandlerProject());
        assertEquals("snmp.handlers.onNotification", config.recordHandlerFunction());
    }

    @Test
    void rejectsRecordHandlerFunctionThatIsNotADottedPath() throws Exception {
        for (String function : new String[] {"onNotification", "snmp..onNotification", "1snmp.handler",
                "snmp.handler.", ".snmp.handler", "snmp handlers.on", "snmp.on()", "snmp.on-off", "snmp/on"}) {
            assertFieldError("recordHandlerFunction", decode(Map.of("recordHandlerFunction", function)));
        }
        for (String function : new String[] {"", "  ", "snmp.handlers.onNotification", "a.b", "_x.y_1",
                " snmp.handlers.onNotification "}) {
            assertEquals(Map.of(), fieldErrors(decode(Map.of("recordHandlerFunction", function))), function);
        }
    }

    @Test
    void decodedVersion100ConfigPassesValidation() throws Exception {
        assertEquals(Map.of(), fieldErrors(decode(Map.of())));
    }

    @Test
    void explicitTrapValuesAreKeptEvenWhenInvalid() throws Exception {
        SNMPDeviceConfig config = decode(Map.of(
                "trapPort", 0,
                "trapBindAddress", "not-an-ip",
                "trapRateLimitPerSecond", 0,
                "trapEnabled", true
        ));

        assertTrue(config.trapEnabled());
        assertEquals(0, config.trapPort());
        assertEquals("not-an-ip", config.trapBindAddress());
        assertEquals(0, config.trapRateLimitPerSecond());
        // 未显式给出的字段仍取默认值
        assertEquals(SNMPDeviceConfig.TrapSecurity.COMMUNITY, config.trapSecurity());
        assertEquals(5000L, config.timeoutMillis());
    }

    @Test
    void rejectsExplicitInvalidPort() throws Exception {
        for (int port : new int[] {0, -1, 65_536}) {
            assertFieldError("trapPort", decode(Map.of("trapPort", port)));
        }
    }

    @Test
    void rejectsBindAddressThatIsNotAnIpLiteral() throws Exception {
        for (String address : new String[] {"", "localhost", "10.0.0.256", "1.2.3", "snmp.example.com"}) {
            assertFieldError("trapBindAddress", decode(Map.of("trapBindAddress", address)));
        }
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapBindAddress", "::"))));
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapBindAddress", "192.168.1.5"))));
    }

    @Test
    void rejectsMalformedSenderEngineIdOnUsmTraps() throws Exception {
        for (String engineId : new String[] {"zz00112233", "0011223", "00112233", "00".repeat(33), "0x", "::"}) {
            assertFieldError("trapSenderEngineId", decode(usmTraps(engineId)));
        }
        assertEquals(Map.of(), fieldErrors(decode(usmTraps("80001F8880AABBCCDD"))));
        assertEquals(Map.of(), fieldErrors(decode(usmTraps("00".repeat(32)))));
        assertEquals(Map.of(), fieldErrors(decode(usmTraps(""))));
    }

    /** As net-snmp, Wireshark and MIB browsers print it: spaces, colons, a 0x prefix, surrounding blanks. */
    @Test
    void acceptsSenderEngineIdAsToolsPrintIt() throws Exception {
        for (String engineId : new String[] {"80 00 1F 88 80 AA BB CC DD", "80:00:1f:88:80:aa:bb:cc:dd",
                "0x80001F8880AABBCCDD", " 80001f8880aabbccdd ", "0X80 00 1F 88 80 AA BB CC DD\n"}) {
            assertEquals(Map.of(), fieldErrors(decode(usmTraps(engineId))), engineId);
            assertEquals("80001F8880AABBCCDD", SNMPDeviceConfig.normalizeEngineId(engineId).toUpperCase(), engineId);
        }
    }

    /** The field only matters to USM Traps; a stale value must not block saving any other change. */
    @Test
    void senderEngineIdIsNotCheckedUnlessTrapsUseUsm() throws Exception {
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapSenderEngineId", "not hex"))));
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapEnabled", false, "trapSecurity", "USM",
                "trapSenderEngineId", "not hex"))));
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapEnabled", true, "trapCommunity", secret(),
                "trapSenderEngineId", "not hex"))));
    }

    private static Map<String, Object> usmTraps(String engineId) {
        return Map.of("trapEnabled", true, "trapSecurity", "USM", "trapSenderEngineId", engineId);
    }

    @Test
    void rejectsMalformedSourceAllowlist() throws Exception {
        for (String allowlist : new String[] {"10.0.0.1,,10.0.0.2", "host.example.com", "10.0.0.0/33",
                "10.0.0.0/x", "fe80::1/129", "10.0.0.256"}) {
            assertFieldError("trapSourceAllowlist", decode(Map.of("trapSourceAllowlist", allowlist)));
        }
        assertEquals(Map.of(), fieldErrors(decode(Map.of(
                "trapSourceAllowlist", "10.0.0.1, 10.1.0.0/16 ,fe80::/10,2001:db8::1"))));
    }

    @Test
    void rejectsRateLimitOutsideOneToTenThousand() throws Exception {
        for (int limit : new int[] {0, -5, 10_001}) {
            assertFieldError("trapRateLimitPerSecond", decode(Map.of("trapRateLimitPerSecond", limit)));
        }
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapRateLimitPerSecond", 1))));
        assertEquals(Map.of(), fieldErrors(decode(Map.of("trapRateLimitPerSecond", 10_000))));
    }

    @Test
    void usmTrapSecurityRequiresV3Device() throws Exception {
        Map<String, Object> usm = Map.of("trapEnabled", true, "trapSecurity", "USM");
        assertEquals(Map.of(), fieldErrors(decode(usm)));

        SNMPDeviceConfig v2c = decode(Map.of(
                "trapEnabled", true, "trapSecurity", "USM",
                "version", "V2C", "readCommunity", secret()));
        assertFieldError("trapSecurity", v2c);
    }

    @Test
    void communityTrapNeedsTrapCommunityOrReadCommunity() throws Exception {
        assertFieldError("trapCommunity", decode(Map.of("trapEnabled", true)));

        assertEquals(Map.of(), fieldErrors(decode(Map.of(
                "trapEnabled", true, "trapCommunity", secret()))));
        assertEquals(Map.of(), fieldErrors(decode(Map.of(
                "trapEnabled", true, "version", "V2C", "readCommunity", secret()))));
    }

    @Test
    void disabledTrapSkipsCrossFieldChecks() throws Exception {
        assertEquals(Map.of(), fieldErrors(decode(Map.of(
                "trapEnabled", false, "trapSecurity", "COMMUNITY"))));
    }

    private static Object secret() {
        return SecretConfig.referenced("provider", "name").toJsonTree();
    }

    private SNMPDeviceConfig decode(Map<String, Object> overrides) throws Exception {
        JsonObject json = JsonParser.parseString(V1_0_0_JSON).getAsJsonObject();
        overrides.forEach((key, value) -> {
            if (value instanceof Boolean flag) {
                json.addProperty(key, flag);
            } else if (value instanceof Number number) {
                json.addProperty(key, number);
            } else if (value instanceof String text) {
                json.addProperty(key, text);
            } else {
                json.add(key, (com.inductiveautomation.ignition.common.gson.JsonElement) value);
            }
        });
        return extensionPoint.decode(json);
    }

    private Map<String, List<String>> fieldErrors(SNMPDeviceConfig config) {
        ValidationErrors.Builder builder = ValidationErrors.newBuilder();
        extensionPoint.validate(config, builder);
        Map<String, List<String>> result = new java.util.HashMap<>();
        builder.build().fieldMessages().forEach(e -> result.put(e.fieldName(), e.messages()));
        return result;
    }

    private void assertFieldError(String field, SNMPDeviceConfig config) {
        Map<String, List<String>> errors = fieldErrors(config);
        assertTrue(errors.containsKey(field), "expected error on " + field + " but got " + errors);
    }
}
