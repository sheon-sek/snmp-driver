package com.jw.snmpdriver.gateway.device;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.snmp.address.AddressResolver;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.runtime.DevicePollHandle;
import com.jw.snmpdriver.snmp.runtime.SnmpValueException;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.ValueRanks;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DynamicAttributeReader {
    private static final Logger logger = LoggerFactory.getLogger(DynamicAttributeReader.class);
    private static final long FAILURE_SUMMARY_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final DeviceContext context;
    private final SNMPDeviceConfig config;
    private final AddressResolver resolver;
    private final SubscriptionBridge subscriptions;
    private final DevicePollHandle pollHandle;
    private final DeviceDiagnostics diagnostics;
    private final Predicate<NodeId> isRealNode;
    private final SNMPDeviceConfig.AccessMode accessMode;
    private final Map<NodeId, FailureLogState> reportedFailures = new ConcurrentHashMap<>();

    public DynamicAttributeReader(
            DeviceContext context,
            SNMPDeviceConfig config,
            AddressResolver resolver,
            SubscriptionBridge subscriptions,
            DevicePollHandle pollHandle,
            DeviceDiagnostics diagnostics,
            Predicate<NodeId> isRealNode,
            SNMPDeviceConfig.AccessMode accessMode
    ) {
        this.context = context;
        this.config = config;
        this.resolver = resolver;
        this.subscriptions = subscriptions;
        this.pollHandle = pollHandle;
        this.diagnostics = diagnostics;
        this.isRealNode = isRealNode;
        this.accessMode = accessMode;
    }

    public Optional<DataValue> read(ReadValueId readValueId) {
        NodeId nodeId = readValueId.getNodeId();
        if (!context.getNamespaceIndex().equals(nodeId.getNamespaceIndex())) {
            return Optional.empty();
        }
        if (isRealNode.test(nodeId)) {
            return Optional.empty();
        }
        Object identifier = nodeId.getIdentifier();
        if (!(identifier instanceof String value)) {
            return Optional.of(reject(
                    nodeId,
                    StatusCodes.Bad_NodeIdInvalid,
                    "item-path",
                    "INVALID_ITEM_PATH: SNMP Item Path must use a string NodeId",
                    null
            ));
        }

        Optional<AttributeId> attribute = AttributeId.from(readValueId.getAttributeId());
        if (attribute.isEmpty()) {
            return Optional.of(reject(
                    nodeId,
                    StatusCodes.Bad_AttributeIdInvalid,
                    "attribute",
                    "Unsupported dynamic SNMP attributeId=" + readValueId.getAttributeId(),
                    null
            ));
        }
        String itemPath = stripDevicePrefix(value);

        ResolvedAddress address;
        try {
            address = resolver.resolve(itemPath);
        } catch (IllegalArgumentException error) {
            return Optional.of(reject(
                    nodeId,
                    StatusCodes.Bad_NodeIdInvalid,
                    "item-path",
                    "INVALID_ITEM_PATH: " + error.getMessage(),
                    error
            ));
        }
        if (!isSupported(attribute.get())) {
            return Optional.of(reject(
                    nodeId,
                    StatusCodes.Bad_AttributeIdInvalid,
                    "attribute",
                    "Unsupported dynamic SNMP attribute=" + attribute.get(),
                    null
            ));
        }
        reportedFailures.remove(nodeId);
        return Optional.of(readLeaf(nodeId, address, attribute.get()));
    }

    private DataValue readLeaf(NodeId nodeId, ResolvedAddress address, AttributeId attribute) {
        boolean writable = address.writableCandidate()
                && accessMode == SNMPDeviceConfig.AccessMode.READ_WRITE;
        return switch (attribute) {
            case NodeId -> value(nodeId);
            case NodeClass -> value(NodeClass.Variable);
            case BrowseName -> value(context.qualifiedName(address.symbol()));
            case DisplayName -> value(LocalizedText.english(address.symbol()));
            case Description -> value(LocalizedText.english(address.description()));
            case DataType -> value(address.wireType().dataType());
            case ValueRank -> value(ValueRanks.Scalar);
            case AccessLevel, UserAccessLevel -> value(AccessLevel.toValue(
                    writable ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY));
            // Advertise an indeterminate minimum so the original client request reaches
            // SubscriptionBridge, where intervals below 1000 ms are rejected instead of
            // being silently revised to 1000 ms by the OPC UA subscription layer.
            case MinimumSamplingInterval -> value(-1.0d);
            case Value -> subscriptions.latest(nodeId)
                    .orElseGet(() -> bad(StatusCodes.Bad_WaitingForInitialData));
            default -> bad(StatusCodes.Bad_AttributeIdInvalid);
        };
    }

    private String stripDevicePrefix(String value) {
        String bracketPrefix = "[" + context.getName() + "]";
        if (value.startsWith(bracketPrefix)) {
            return value.substring(bracketPrefix.length());
        }
        String pathPrefix = context.getName() + "/";
        if (value.startsWith(pathPrefix)) {
            return value.substring(pathPrefix.length());
        }
        return value;
    }

    private DataValue reject(
            NodeId nodeId,
            long statusCode,
            String category,
            String message,
            Throwable cause
    ) {
        SnmpValueException failure = new SnmpValueException(
                statusCode,
                category,
                message,
                cause
        );
        String signature = category + "|" + message;
        long now = System.nanoTime();
        reportedFailures.compute(nodeId, (ignored, existing) -> {
            if (existing == null || !existing.signature.equals(signature)) {
                logRejected(nodeId, category, message);
                return new FailureLogState(signature, now, 0L);
            }
            existing.suppressed++;
            if (now - existing.lastWarnNanos >= FAILURE_SUMMARY_NANOS) {
                logger.warn(
                        "SNMP repeated dynamic read rejections device={} host={} port={} op=READ nodeId={} version={} category={} message={} suppressed={}",
                        context.getName(), config.host(), config.port(), nodeId, config.version(),
                        category, message, existing.suppressed
                );
                existing.lastWarnNanos = now;
                existing.suppressed = 0L;
            }
            return existing;
        });
        diagnostics.recordLocalFailure(failure, pollHandle.snapshot());
        return bad(statusCode);
    }

    private void logRejected(NodeId nodeId, String category, String message) {
        logger.warn(
                "SNMP dynamic read rejected device={} host={} port={} op=READ nodeId={} version={} category={} message={}",
                context.getName(), config.host(), config.port(), nodeId, config.version(),
                category, message
        );
    }

    private static boolean isSupported(AttributeId attribute) {
        return switch (attribute) {
            case NodeId, NodeClass, BrowseName, DisplayName, Description, DataType,
                    ValueRank, AccessLevel, UserAccessLevel, MinimumSamplingInterval, Value -> true;
            default -> false;
        };
    }

    private static DataValue value(Object value) {
        return new DataValue(new Variant(value));
    }

    private static DataValue bad(long statusCode) {
        return new DataValue(statusCode);
    }

    private static final class FailureLogState {
        private final String signature;
        private long lastWarnNanos;
        private long suppressed;

        private FailureLogState(String signature, long lastWarnNanos, long suppressed) {
            this.signature = signature;
            this.lastWarnNanos = lastWarnNanos;
            this.suppressed = suppressed;
        }
    }
}

