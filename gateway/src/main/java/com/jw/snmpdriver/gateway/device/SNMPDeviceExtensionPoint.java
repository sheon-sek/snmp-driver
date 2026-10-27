package com.jw.snmpdriver.gateway.device;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.config.DecodingException;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.MenuAction;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import com.jw.snmpdriver.common.ModuleConstants;
import com.jw.snmpdriver.gateway.service.ServiceBundle;
import com.jw.snmpdriver.snmp.trap.TrapListenerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SNMPDeviceExtensionPoint extends DeviceExtensionPoint<SNMPDeviceConfig> {
    public static final String TYPE_ID = ModuleConstants.DEVICE_TYPE_ID;
    private static final Logger logger = LoggerFactory.getLogger(SNMPDeviceExtensionPoint.class);

    private final ServiceBundle services;

    public SNMPDeviceExtensionPoint(ServiceBundle services) {
        super(TYPE_ID, "SNMPDevice.Meta.DisplayName", "SNMPDevice.Meta.Description", SNMPDeviceConfig.class);
        this.services = Objects.requireNonNull(services);
    }

    /**
     * 1.0.0 配置没有 trap* 字段，1.1.0 早期配置没有 recordHandler* 字段；record 缺字段只得 0/false/null，
     * 所以只为缺失的这两组字段补默认值，显式给出的值（包括非法值）原样保留，交给 {@link #validate}。
     */
    @Override
    public SNMPDeviceConfig decode(JsonElement json) throws DecodingException {
        if (json != null && json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject().deepCopy();
            JsonObject defaults = encode(SNMPDeviceConfig.defaults()).getAsJsonObject();
            for (RecordComponent component : SNMPDeviceConfig.class.getRecordComponents()) {
                String name = component.getName();
                boolean defaulted = name.startsWith("trap") || name.startsWith("record");
                if (defaulted && !object.has(name) && defaults.has(name)) {
                    object.add(name, defaults.get(name));
                }
            }
            return super.decode(object);
        }
        return super.decode(json);
    }

    @Override
    public Optional<SNMPDeviceConfig> defaultSettings() {
        return Optional.of(SNMPDeviceConfig.defaults());
    }

    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(new ExtensionPointResourceForm(
                DEVICE_RESOURCE_TYPE,
                "Device Connection",
                TYPE_ID,
                SchemaUtil.fromType(DeviceProfileConfig.class),
                SchemaUtil.fromType(SNMPDeviceConfig.class),
                Set.of()
        ));
    }

    @Override
    public List<MenuAction> getMenuActions() {
        return List.of();
    }

    @Override
    protected Device createDevice(
            DeviceContext context,
            DeviceProfileConfig profile,
            SNMPDeviceConfig config
    ) {
        SNMPDeviceConfig effective = config == null ? SNMPDeviceConfig.defaults() : config;
        try {
            SNMPDevice device = new SNMPDevice(context, effective, services);
            services.registerDevice(context.getName(), device);
            return device;
        } catch (RuntimeException error) {
            logger.error(
                    "Failed to create SNMP Device device={} host={} port={} version={} category=initialization",
                    context.getName(), effective.host(), effective.port(), effective.version(), error
            );
            throw error;
        }
    }

    @Override
    protected void validate(SNMPDeviceConfig settings, ValidationErrors.Builder errors) {
        SNMPDeviceConfig config = settings == null ? SNMPDeviceConfig.defaults() : settings;

        errors.requireNotBlank("host", config.host());
        errors.checkField(config.port() >= 1 && config.port() <= 65_535,
                "port", "Port must be 1-65535.");
        errors.requireNotNull("ipFamily", config.ipFamily());
        errors.requireNotNull("version", config.version());
        errors.requireNotNull("accessMode", config.accessMode());
        errors.checkField(config.timeoutMillis() >= 100 && config.timeoutMillis() <= 60_000,
                "timeoutMillis", "Timeout must be 100-60000 ms.");
        errors.checkField(config.retries() >= 0 && config.retries() <= 10,
                "retries", "Retries must be 0-10.");
        errors.checkField(config.maxOidsPerRequest() >= 1 && config.maxOidsPerRequest() <= 100,
                "maxOidsPerRequest", "Max OIDs per request must be 1-100.");
        errors.checkField(config.maxRequestBytes() >= 484 && config.maxRequestBytes() <= 65_535,
                "maxRequestBytes", "Max request bytes must be 484-65535.");
        errors.checkField(config.maxInflightPerDevice() >= 1 && config.maxInflightPerDevice() <= 8,
                "maxInflightPerDevice", "Max in-flight requests must be 1-8.");

        if (config.version() == SNMPDeviceConfig.Version.V1
                || config.version() == SNMPDeviceConfig.Version.V2C) {
            errors.requireNotNull("readCommunity", config.readCommunity());
            if (config.accessMode() == SNMPDeviceConfig.AccessMode.READ_WRITE) {
                errors.requireNotNull("writeCommunity", config.writeCommunity());
            }
        }

        if (config.version() == SNMPDeviceConfig.Version.V3) {
            errors.requireNotBlank("securityName", config.securityName());
            errors.requireNotNull("securityLevel", config.securityLevel());
            if (config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_NO_PRIV
                    || config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
                errors.requireNotNull("authProtocol", config.authProtocol());
                errors.requireNotNull("authPassphrase", config.authPassphrase());
            }
            if (config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
                errors.requireNotNull("privacyProtocol", config.privacyProtocol());
                errors.requireNotNull("privacyPassphrase", config.privacyPassphrase());
            }
        }

        validateTrap(config, errors);
    }

    private static void validateTrap(SNMPDeviceConfig config, ValidationErrors.Builder errors) {
        errors.checkField(config.trapBindAddress() != null && isIpLiteral(config.trapBindAddress()),
                "trapBindAddress", "Trap bind address must be an IP literal such as 0.0.0.0 or ::.");
        errors.checkField(config.trapPort() >= 1 && config.trapPort() <= 65_535,
                "trapPort", "Trap port must be 1-65535.");
        errors.requireNotNull("trapSecurity", config.trapSecurity());
        // only USM Traps use it; a stale value must not block saving unrelated changes
        if (config.trapEnabled() && config.trapSecurity() == SNMPDeviceConfig.TrapSecurity.USM) {
            errors.checkField(isValidEngineId(config.trapSenderEngineId()), "trapSenderEngineId",
                    "Trap sender engine ID must be 10-64 hex digits (5-32 bytes); spaces, colons and 0x are allowed.");
        }
        errors.checkField(isValidAllowlist(config.trapSourceAllowlist()),
                "trapSourceAllowlist", "Trap source allowlist must be comma-separated IP addresses or CIDR ranges.");
        errors.checkField(config.trapRateLimitPerSecond() >= 1 && config.trapRateLimitPerSecond() <= 10_000,
                "trapRateLimitPerSecond", "Trap rate limit must be 1-10000 per second.");
        // only the format: whether the project and function exist is checked at runtime (ADR 0007)
        String function = config.recordHandlerFunction();
        errors.checkField(function == null || function.isBlank()
                        || SNMPDeviceConfig.isValidRecordHandlerFunction(function),
                "recordHandlerFunction",
                "Record handler function must be a dotted Script Library path such as snmp.handlers.onNotification.");
        errors.checkField(config.recordQueueCapacity() >= SNMPDeviceConfig.MIN_RECORD_QUEUE_CAPACITY
                        && config.recordQueueCapacity() <= SNMPDeviceConfig.MAX_RECORD_QUEUE_CAPACITY,
                "recordQueueCapacity", "Record queue capacity must be 100-100000.");

        if (!config.trapEnabled()) {
            return;
        }
        if (config.trapSecurity() == SNMPDeviceConfig.TrapSecurity.USM) {
            errors.checkField(config.version() == SNMPDeviceConfig.Version.V3,
                    "trapSecurity", "USM Trap security requires SNMP Version V3.");
        } else if (config.trapSecurity() == SNMPDeviceConfig.TrapSecurity.COMMUNITY) {
            errors.checkField(config.trapCommunity() != null || config.readCommunity() != null,
                    "trapCommunity", "Set Trap Community or Read Community for COMMUNITY Trap security.");
        }
    }

    private static boolean isValidEngineId(String engineId) {
        if (engineId == null || engineId.isBlank()) {
            return true;
        }
        String hex = SNMPDeviceConfig.normalizeEngineId(engineId);
        return hex.matches("[0-9a-fA-F]{10,64}") && hex.length() % 2 == 0;
    }

    private static boolean isValidAllowlist(String allowlist) {
        if (allowlist == null || allowlist.isBlank()) {
            return true;
        }
        for (String entry : allowlist.split(",", -1)) {
            String trimmed = entry.trim();
            int slash = trimmed.indexOf('/');
            String address = slash < 0 ? trimmed : trimmed.substring(0, slash);
            if (!isIpLiteral(address)) {
                return false;
            }
            if (slash >= 0) {
                int max = address.indexOf(':') >= 0 ? 128 : 32;
                String prefix = trimmed.substring(slash + 1);
                if (!prefix.matches("[0-9]{1,3}") || Integer.parseInt(prefix) > max) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isIpLiteral(String text) {
        return TrapListenerRegistry.parseIpLiteral(text) != null;
    }
}
