package com.jw.snmpdriver.gateway.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.secrets.SecretException;
import com.jw.snmpdriver.common.ModuleConstants;
import com.jw.snmpdriver.gateway.service.ServiceBundle;
import com.jw.snmpdriver.snmp.address.AddressResolver;
import com.jw.snmpdriver.snmp.runtime.DevicePollHandle;
import com.jw.snmpdriver.snmp.runtime.DevicePollSnapshot;
import com.jw.snmpdriver.snmp.runtime.SnmpConfigurationException;
import com.jw.snmpdriver.snmp.runtime.SnmpEndpoint;
import com.jw.snmpdriver.snmp.runtime.SnmpFailureClassifier;
import com.jw.snmpdriver.snmp.runtime.SnmpValueCodec;
import com.jw.snmpdriver.snmp.runtime.SnmpValueException;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.AddressSpace.ReadContext;
import org.eclipse.milo.opcua.sdk.server.AddressSpace.RevisedDataItemParameters;
import org.eclipse.milo.opcua.sdk.server.AddressSpace.WriteContext;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SNMPDevice extends ManagedAddressSpaceWithLifecycle implements Device {
    private static final Logger logger = LoggerFactory.getLogger(SNMPDevice.class);

    private final DeviceContext context;
    private final SNMPDeviceConfig config;
    private final ServiceBundle services;
    private final AddressResolver resolver = new AddressResolver();
    private final SnmpValueCodec codec = new SnmpValueCodec();
    private final SubscriptionModel subscriptionModel;
    private final DeviceDiagnostics diagnostics;

    private volatile String status = "Starting";
    private volatile boolean shuttingDown;
    private volatile DevicePollHandle pollHandle;
    private volatile SubscriptionBridge subscriptionBridge;
    private volatile DynamicAttributeReader attributeReader;
    private volatile DynamicAttributeWriter attributeWriter;

    public SNMPDevice(DeviceContext context, SNMPDeviceConfig config, ServiceBundle services) {
        super(context.getServer());
        this.context = context;
        this.config = config;
        this.services = services;
        this.subscriptionModel = new SubscriptionModel(context.getServer(), this);
        this.diagnostics = new DeviceDiagnostics(this, context);

        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::onStartup);
        getLifecycleManager().addShutdownTask(this::onShutdown);
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public RevisedDataItemParameters onCreateDataItem(
            ReadValueId readValueId,
            Double requestedInterval,
            UInteger requestedQueueSize
    ) {
        return reviseDataItem(requestedInterval, requestedQueueSize);
    }

    @Override
    public RevisedDataItemParameters onModifyDataItem(
            ReadValueId readValueId,
            Double requestedInterval,
            UInteger requestedQueueSize
    ) {
        return reviseDataItem(requestedInterval, requestedQueueSize);
    }

    private RevisedDataItemParameters reviseDataItem(
            Double requestedInterval,
            UInteger requestedQueueSize
    ) {
        double revised;
        if (requestedInterval == null
                || requestedInterval.isNaN()
                || requestedInterval < 0.0) {
            revised = ModuleConstants.MIN_SAMPLING_INTERVAL_MS;
        } else {
            revised = requestedInterval;
        }
        return new RevisedDataItemParameters(revised, requestedQueueSize);
    }

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
        try {
            subscriptionModel.onDataItemsCreated(dataItems);
        } catch (RuntimeException error) {
            recordDataItemModelFailure("CREATE", sizeOf(dataItems), error);
            throw error;
        }
        SubscriptionBridge bridge = subscriptionBridge;
        if (bridge != null) {
            bridge.created(dataItems);
        }
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
        try {
            subscriptionModel.onDataItemsModified(dataItems);
        } catch (RuntimeException error) {
            recordDataItemModelFailure("MODIFY", sizeOf(dataItems), error);
            throw error;
        }
        SubscriptionBridge bridge = subscriptionBridge;
        if (bridge != null) {
            bridge.modified(dataItems);
        }
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
        try {
            subscriptionModel.onDataItemsDeleted(dataItems);
        } catch (RuntimeException error) {
            recordDataItemModelFailure("DELETE", sizeOf(dataItems), error);
            throw error;
        }
        SubscriptionBridge bridge = subscriptionBridge;
        if (bridge != null) {
            bridge.deleted(dataItems);
        }
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        try {
            subscriptionModel.onMonitoringModeChanged(monitoredItems);
        } catch (RuntimeException error) {
            recordDataItemModelFailure("MONITORING_MODE", sizeOf(monitoredItems), error);
            throw error;
        }
        SubscriptionBridge bridge = subscriptionBridge;
        if (bridge != null) {
            bridge.monitoringModeChanged(monitoredItems);
        }
    }

    @Override
    public List<DataValue> read(
            ReadContext readContext,
            Double maxAge,
            TimestampsToReturn timestamps,
            List<ReadValueId> readValueIds
    ) {
        DynamicAttributeReader reader = attributeReader;
        if (reader == null) {
            return super.read(readContext, maxAge, timestamps, readValueIds);
        }

        List<DataValue> results = new ArrayList<>(readValueIds.size());
        for (ReadValueId id : readValueIds) {
            Optional<DataValue> dynamic;
            try {
                dynamic = reader.read(id);
            } catch (RuntimeException error) {
                SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
                logger.error(
                        "Unhandled SNMP dynamic read failure device={} host={} port={} nodeId={} version={} category={} message={}",
                        context.getName(), config.host(), config.port(), id.getNodeId(), config.version(),
                        failure.category(), failure.message(), error
                );
                diagnostics.recordLocalFailure(error, currentSnapshot());
                handleFailure(error);
                results.add(new DataValue(failure.statusCode()));
                continue;
            }
            results.add(dynamic.orElseGet(() ->
                    super.read(readContext, maxAge, timestamps, List.of(id)).get(0)));
        }
        return results;
    }

    @Override
    public List<StatusCode> write(
            WriteContext writeContext,
            List<WriteValue> writeValues
    ) {
        DynamicAttributeWriter writer = attributeWriter;
        if (writer == null) {
            return super.write(writeContext, writeValues);
        }

        List<StatusCode> results = new ArrayList<>(writeValues.size());
        for (WriteValue writeValue : writeValues) {
            Optional<StatusCode> dynamic;
            try {
                dynamic = writer.write(writeValue);
            } catch (RuntimeException error) {
                SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
                logger.error(
                        "Unhandled SNMP dynamic write failure device={} host={} port={} nodeId={} version={} category={} message={}",
                        context.getName(), config.host(), config.port(), writeValue.getNodeId(),
                        config.version(), failure.category(), failure.message(), error
                );
                diagnostics.recordLocalFailure(error, currentSnapshot());
                handleFailure(error);
                results.add(new StatusCode(failure.statusCode()));
                continue;
            }
            results.add(dynamic.orElseGet(() ->
                    super.write(writeContext, List.of(writeValue)).get(0)));
        }
        return results;
    }

    private DevicePollSnapshot currentSnapshot() {
        DevicePollHandle handle = pollHandle;
        return handle == null ? DevicePollSnapshot.empty() : handle.snapshot();
    }

    private void recordDataItemModelFailure(
            String lifecycleEvent,
            int itemCount,
            RuntimeException error
    ) {
        SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
        logger.error(
                "SNMP DataItem model callback failed device={} host={} port={} event={} itemCount={} version={} category={} message={}",
                context.getName(), config.host(), config.port(), lifecycleEvent, itemCount,
                config.version(), failure.category(), failure.message(), error
        );
        diagnostics.recordLocalFailure(error, currentSnapshot());
        handleFailure(error);
    }

    private static int sizeOf(List<?> values) {
        return values == null ? -1 : values.size();
    }

    private void onStartup() {
        try {
            createAddressSpace();
            services.ensureStarted();
            pollHandle = createPollHandle();
            subscriptionBridge = new SubscriptionBridge(
                    context,
                    config,
                    resolver,
                    pollHandle,
                    codec,
                    diagnostics,
                    getNodeManager()::containsNode,
                    this::markRunning,
                    this::handleFailure,
                    this::handleActivity
            );
            attributeReader = new DynamicAttributeReader(
                    context,
                    config,
                    resolver,
                    subscriptionBridge,
                    pollHandle,
                    diagnostics,
                    getNodeManager()::containsNode,
                    config.accessMode()
            );
            attributeWriter = new DynamicAttributeWriter(
                    context,
                    config,
                    resolver,
                    codec,
                    pollHandle,
                    diagnostics,
                    getNodeManager()::containsNode,
                    this::markRunning,
                    this::handleFailure
            );

            setStatus("Idle");
            List<DataItem> restoredItems =
                    context.getSubscriptionModel().getDataItems(context.getName());
            subscriptionModel.onDataItemsCreated(restoredItems);
            subscriptionBridge.restore(restoredItems);
            handleActivity(pollHandle.snapshot());
            logger.info(
                    "SNMP Device started device={} host={} port={} version={}",
                    context.getName(), config.host(), config.port(), config.version()
            );
        } catch (SecretException | SnmpConfigurationException error) {
            cleanupAfterStartupFailure();
            String message = error instanceof SecretException
                    ? "Failed to resolve a required SNMP credential"
                    : error.getMessage();
            SnmpValueException failure = new SnmpValueException(
                    org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_ConfigurationError,
                    "configuration",
                    message == null || message.isBlank()
                            ? "SNMP Device configuration is invalid"
                            : message,
                    error
            );
            String template = "SNMP Device configuration failed device={} host={} port={} version={} category=configuration message={}";
            if (error instanceof SecretException) {
                logger.error(
                        template,
                        context.getName(), config.host(), config.port(), config.version(),
                        failure.getMessage()
                );
            } else {
                logger.error(
                        template,
                        context.getName(), config.host(), config.port(), config.version(),
                        failure.getMessage(), error
                );
            }
            setStatus("Errored");
            diagnostics.recordLocalFailure(failure, DevicePollSnapshot.empty());
        } catch (RuntimeException error) {
            cleanupAfterStartupFailure();
            SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
            logger.error(
                    "SNMP Device runtime initialization failed device={} host={} port={} version={} category={} message={}",
                    context.getName(), config.host(), config.port(), config.version(),
                    failure.category(), failure.message(), error
            );
            setStatus("Errored");
            diagnostics.recordLocalFailure(error, DevicePollSnapshot.empty());
        }
    }

    private void createAddressSpace() {
        UaFolderNode root = new UaFolderNode(
                getNodeContext(),
                context.getDeviceNodeId(),
                context.qualifiedName("[" + context.getName() + "]"),
                LocalizedText.english("[" + context.getName() + "]")
        );
        getNodeManager().addNode(root);
        root.addReference(new Reference(
                root.getNodeId(),
                NodeIds.Organizes,
                context.getRootNodeId().expanded(),
                Reference.Direction.INVERSE
        ));
        diagnostics.createUnder(root);
    }

    private DevicePollHandle createPollHandle() throws SecretException {
        validateRuntimeSettings();
        String readCommunity = "";
        String writeCommunity = "";
        String authPassphrase = "";
        String privacyPassphrase = "";

        if (config.version() == SNMPDeviceConfig.Version.V1
                || config.version() == SNMPDeviceConfig.Version.V2C) {
            readCommunity = requiredSecret(config.readCommunity(), "Read Community");
            if (config.accessMode() == SNMPDeviceConfig.AccessMode.READ_WRITE) {
                writeCommunity = requiredSecret(config.writeCommunity(), "Write Community");
            }
        } else {
            if (config.securityName() == null || config.securityName().isBlank()) {
                throw new SnmpConfigurationException("SNMPv3 Security Name is required");
            }
            if (config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_NO_PRIV
                    || config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
                authPassphrase = requiredPassphrase(config.authPassphrase(), "Auth Passphrase");
            }
            if (config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
                privacyPassphrase = requiredPassphrase(config.privacyPassphrase(), "Privacy Passphrase");
            }
        }

        return services.runtime().registerDevice(new SnmpEndpoint(
                context.getName(),
                config.host(),
                config.port(),
                config.ipFamily(),
                config.version(),
                readCommunity,
                writeCommunity,
                config.securityName() == null ? "" : config.securityName(),
                config.securityLevel(),
                config.authProtocol(),
                authPassphrase,
                config.privacyProtocol(),
                privacyPassphrase,
                config.contextName() == null ? "" : config.contextName(),
                config.accessMode(),
                config.timeoutMillis(),
                config.retries(),
                config.maxOidsPerRequest(),
                config.maxRequestBytes(),
                config.maxInflightPerDevice()
        ));
    }

    private void validateRuntimeSettings() {
        require(config.host() != null && !config.host().isBlank(), "Host is required");
        require(config.port() >= 1 && config.port() <= 65_535,
                "Port must be between 1 and 65535");
        require(config.ipFamily() != null, "IP Family is required");
        require(config.version() != null, "SNMP Version is required");
        require(config.accessMode() != null, "Access Mode is required");
        require(config.timeoutMillis() >= 100L && config.timeoutMillis() <= 60_000L,
                "Timeout must be between 100 and 60000 ms");
        require(config.retries() >= 0 && config.retries() <= 10,
                "Retries must be between 0 and 10");
        require(config.maxOidsPerRequest() >= 1 && config.maxOidsPerRequest() <= 100,
                "Max OIDs per Request must be between 1 and 100");
        require(config.maxRequestBytes() >= 484 && config.maxRequestBytes() <= 65_535,
                "Max Request Bytes must be between 484 and 65535");
        require(config.maxInflightPerDevice() >= 1 && config.maxInflightPerDevice() <= 8,
                "Max In-flight Requests must be between 1 and 8");

        if (config.version() == SNMPDeviceConfig.Version.V3) {
            require(config.securityLevel() != null, "SNMPv3 Security Level is required");
            if (config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_NO_PRIV
                    || config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
                require(config.authProtocol() != null, "SNMPv3 Auth Protocol is required");
            }
            if (config.securityLevel() == SNMPDeviceConfig.SecurityLevel.AUTH_PRIV) {
                require(config.privacyProtocol() != null, "SNMPv3 Privacy Protocol is required");
            }
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new SnmpConfigurationException(message);
        }
    }

    private String requiredSecret(SecretConfig secretConfig, String field) throws SecretException {
        String value = secret(secretConfig);
        if (value.isBlank()) {
            throw new SnmpConfigurationException(field + " is required");
        }
        return value;
    }

    private String requiredPassphrase(SecretConfig secretConfig, String field)
            throws SecretException {
        String value = requiredSecret(secretConfig, field);
        if (value.length() < 8) {
            throw new SnmpConfigurationException(field + " must contain at least 8 characters");
        }
        return value;
    }

    private String secret(SecretConfig secretConfig) throws SecretException {
        if (secretConfig == null) {
            return "";
        }
        try (Plaintext plaintext = Secret.create(
                context.getGatewayContext(), secretConfig).getPlaintext()) {
            return plaintext.getAsString();
        }
    }

    private void handleActivity(DevicePollSnapshot snapshot) {
        diagnostics.refresh(snapshot);
        if (shuttingDown || "Errored".equals(status)) {
            return;
        }
        if (snapshot.activeOidCount() == 0) {
            setStatus("Idle");
        } else if ("Idle".equals(status) || "Starting".equals(status)) {
            setStatus("Connecting");
        }
    }

    private void markRunning() {
        if (!shuttingDown && !"Errored".equals(status)) {
            DevicePollHandle handle = pollHandle;
            setStatus(handle != null && handle.snapshot().activeOidCount() > 0
                    ? "Running"
                    : "Idle");
        }
    }

    private void handleFailure(Throwable error) {
        if (shuttingDown || "Errored".equals(status)) {
            return;
        }
        SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
        if ("license".equals(failure.category())) {
            setStatus("Unlicensed");
        } else if (failure.legalResponse()) {
            markRunning();
        } else if (failure.circuitFailure()) {
            setStatus("Degraded");
        } else if ("resource".equals(failure.category())) {
            setStatus("Degraded");
        } else if (failure.unexpected()) {
            setStatus("Errored");
        }
    }

    private synchronized void setStatus(String nextStatus) {
        String previousStatus = status;
        if (nextStatus.equals(previousStatus)) {
            diagnostics.updateStatus(nextStatus);
            return;
        }
        status = nextStatus;
        if ("Unlicensed".equals(nextStatus)
                || "Degraded".equals(nextStatus)
                || "Errored".equals(nextStatus)) {
            logger.warn(
                    "SNMP Device status transition device={} host={} port={} version={} from={} to={}",
                    context.getName(), config.host(), config.port(), config.version(),
                    previousStatus, nextStatus
            );
        } else {
            logger.info(
                    "SNMP Device status transition device={} host={} port={} version={} from={} to={}",
                    context.getName(), config.host(), config.port(), config.version(),
                    previousStatus, nextStatus
            );
        }
        diagnostics.updateStatus(nextStatus);
    }

    private void onShutdown() {
        shuttingDown = true;
        attributeReader = null;
        attributeWriter = null;
        SubscriptionBridge bridge = subscriptionBridge;
        subscriptionBridge = null;
        if (bridge != null) {
            try {
                bridge.shutdown();
            } catch (RuntimeException error) {
                logger.warn(
                        "Failed to stop SNMP subscription bridge device={} host={} port={} version={} category=shutdown",
                        context.getName(), config.host(), config.port(), config.version(), error
                );
            }
        }
        DevicePollHandle handle = pollHandle;
        pollHandle = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (RuntimeException error) {
                logger.warn(
                        "Failed to close SNMP poll handle device={} host={} port={} version={} category=shutdown",
                        context.getName(), config.host(), config.port(), config.version(), error
                );
            }
        }
        try {
            services.unregisterDevice(context.getName(), this);
        } catch (RuntimeException error) {
            logger.warn(
                    "Failed to unregister SNMP Device device={} host={} port={} version={} category=shutdown",
                    context.getName(), config.host(), config.port(), config.version(), error
            );
        }
        logger.info(
                "SNMP Device stopped device={} host={} port={} version={}",
                context.getName(), config.host(), config.port(), config.version()
        );
    }

    private void cleanupAfterStartupFailure() {
        attributeReader = null;
        attributeWriter = null;
        SubscriptionBridge bridge = subscriptionBridge;
        subscriptionBridge = null;
        if (bridge != null) {
            try {
                bridge.shutdown();
            } catch (RuntimeException cleanupError) {
                logger.warn(
                        "Failed to clean up SNMP subscription bridge after startup failure device={} host={} port={} version={}",
                        context.getName(), config.host(), config.port(), config.version(), cleanupError
                );
            }
        }
        DevicePollHandle handle = pollHandle;
        pollHandle = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (RuntimeException cleanupError) {
                logger.warn(
                        "Failed to clean up SNMP poll handle after startup failure device={} host={} port={} version={}",
                        context.getName(), config.host(), config.port(), config.version(), cleanupError
                );
            }
        }
    }
}
