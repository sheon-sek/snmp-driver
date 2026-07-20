package com.jw.snmpdriver.gateway.device;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    }
}

