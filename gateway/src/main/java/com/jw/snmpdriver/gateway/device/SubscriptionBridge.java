package com.jw.snmpdriver.gateway.device;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.snmp.address.AddressResolver;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.address.TrapItemPath;
import com.jw.snmpdriver.snmp.runtime.DevicePollHandle;
import com.jw.snmpdriver.snmp.runtime.DevicePollSnapshot;
import com.jw.snmpdriver.snmp.runtime.PollSubscription;
import com.jw.snmpdriver.snmp.runtime.SnmpFailureClassifier;
import com.jw.snmpdriver.snmp.runtime.SnmpValueCodec;
import com.jw.snmpdriver.snmp.runtime.SnmpValueException;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SubscriptionBridge {
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionBridge.class);

    private final DeviceContext context;
    private final SNMPDeviceConfig config;
    private final AddressResolver resolver;
    private final DevicePollHandle pollHandle;
    private final SnmpValueCodec codec;
    private final DeviceDiagnostics diagnostics;
    private final Predicate<NodeId> isRealNode;
    private final Runnable onLegalResponse;
    private final Consumer<Throwable> onFailure;
    private final Consumer<DevicePollSnapshot> onActivityChanged;
    private final TrapDiagnostics traps;
    private final Map<DataItemKey, DataItem> items = new ConcurrentHashMap<>();
    private final Map<DataItemKey, ActiveSubscription> active = new ConcurrentHashMap<>();
    private final Map<NodeId, DataValue> latestValues = new ConcurrentHashMap<>();
    private final Map<NodeId, CodecFailureState> codecFailures = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public SubscriptionBridge(
            DeviceContext context,
            SNMPDeviceConfig config,
            AddressResolver resolver,
            DevicePollHandle pollHandle,
            SnmpValueCodec codec,
            DeviceDiagnostics diagnostics,
            Predicate<NodeId> isRealNode,
            Runnable onLegalResponse,
            Consumer<Throwable> onFailure,
            Consumer<DevicePollSnapshot> onActivityChanged
    ) {
        this(context, config, resolver, pollHandle, codec, diagnostics, isRealNode, onLegalResponse, onFailure,
                onActivityChanged, null);
    }

    public SubscriptionBridge(
            DeviceContext context,
            SNMPDeviceConfig config,
            AddressResolver resolver,
            DevicePollHandle pollHandle,
            SnmpValueCodec codec,
            DeviceDiagnostics diagnostics,
            Predicate<NodeId> isRealNode,
            Runnable onLegalResponse,
            Consumer<Throwable> onFailure,
            Consumer<DevicePollSnapshot> onActivityChanged,
            TrapDiagnostics traps
    ) {
        this.context = context;
        this.config = config;
        this.resolver = resolver;
        this.pollHandle = pollHandle;
        this.codec = codec;
        this.diagnostics = diagnostics;
        this.isRealNode = isRealNode;
        this.onLegalResponse = onLegalResponse;
        this.onFailure = onFailure;
        this.onActivityChanged = onActivityChanged;
        this.traps = traps;
    }

    public void restore(List<DataItem> restoredItems) {
        restoredItems.forEach(item -> safeReplace(item, "RESTORE"));
    }

    public void created(List<DataItem> createdItems) {
        createdItems.forEach(item -> safeReplace(item, "CREATE"));
    }

    public void modified(List<DataItem> modifiedItems) {
        modifiedItems.forEach(item -> safeReplace(item, "MODIFY"));
    }

    public void deleted(List<DataItem> deletedItems) {
        deletedItems.forEach(item -> {
            DataItemKey key = DataItemKey.of(item);
            closeActive(key, "DELETE");
            items.remove(key);
            NodeId nodeId = item.getReadValueId().getNodeId();
            boolean stillTracked = items.values().stream()
                    .anyMatch(tracked -> tracked.getReadValueId().getNodeId().equals(nodeId));
            if (!stillTracked) {
                latestValues.remove(nodeId);
                codecFailures.remove(nodeId);
            }
        });
        refreshActivity();
    }

    public void monitoringModeChanged(List<MonitoredItem> monitoredItems) {
        monitoredItems.stream()
                .filter(DataItem.class::isInstance)
                .map(DataItem.class::cast)
                .forEach(item -> safeReplace(item, "MONITORING_MODE"));
    }

    public Optional<DataValue> latest(NodeId nodeId) {
        return Optional.ofNullable(latestValues.get(nodeId));
    }

    public void shutdown() {
        stopped = true;
        active.forEach((key, subscription) ->
                closeSubscription(key, subscription, "SHUTDOWN"));
        active.clear();
        items.clear();
        codecFailures.clear();
    }

    private void replace(DataItem item) {
        if (stopped) {
            return;
        }
        DataItemKey key = DataItemKey.of(item);
        items.put(key, item);
        closeActive(key, "REPLACE");
        activate(item, key);
        refreshActivity();
    }

    private void safeReplace(DataItem item, String lifecycleEvent) {
        try {
            replace(item);
        } catch (RuntimeException error) {
            SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
            NodeId nodeId = item.getReadValueId().getNodeId();
            DataValue value = new DataValue(failure.statusCode());
            latestValues.put(nodeId, value);
            try {
                item.setValue(value);
            } catch (RuntimeException publishError) {
                SnmpFailureClassifier.Failure publishFailure =
                        SnmpFailureClassifier.classify(publishError);
                logger.error(
                        "Failed to publish SNMP DataItem lifecycle error device={} host={} port={} event={} itemId={} nodeId={} version={} category={} message={}",
                        context.getName(), config.host(), config.port(), lifecycleEvent,
                        item.getId(), nodeId, config.version(), publishFailure.category(),
                        publishFailure.message(), publishError
                );
            }
            logger.error(
                    "SNMP DataItem lifecycle failed device={} host={} port={} event={} itemId={} nodeId={} version={} category={} message={}",
                    context.getName(), config.host(), config.port(), lifecycleEvent, item.getId(),
                    nodeId, config.version(), failure.category(),
                    failure.message(), error
            );
            recordLocalFailureSafely(error, lifecycleEvent, nodeId);
            notifyDeviceFailure(error, lifecycleEvent, nodeId);
        }
    }

    private void activate(DataItem item, DataItemKey key) {
        if (stopped
                || !item.isSamplingEnabled()
                || !AttributeId.Value.isEqual(item.getReadValueId().getAttributeId())) {
            return;
        }

        NodeId nodeId = item.getReadValueId().getNodeId();
        if (isRealNode.test(nodeId)) {
            return;
        }

        Optional<TrapItemPath> trapPath;
        ResolvedAddress address;
        try {
            String itemPath = stripDevicePrefix(nodeId);
            trapPath = traps == null ? Optional.empty() : resolver.resolveTrap(itemPath);
            address = trapPath.isPresent() ? null : resolver.resolve(itemPath);
        } catch (IllegalArgumentException error) {
            SnmpValueException failure = new SnmpValueException(
                    StatusCodes.Bad_NodeIdInvalid,
                    "item-path",
                    "INVALID_ITEM_PATH: " + error.getMessage(),
                    error
            );
            logger.warn(
                    "Invalid SNMP Item Path device={} host={} port={} op=GET nodeId={} version={} category=item-path message={}",
                    context.getName(),
                    config.host(),
                    config.port(),
                    nodeId,
                    config.version(),
                    failure.getMessage()
            );
            setLocalFailure(item, nodeId, failure.statusCode(), failure);
            return;
        }
        if (trapPath.isPresent()) {
            activateTrapPath(item, key, nodeId, trapPath.get());
            return;
        }

        long intervalMillis;
        try {
            intervalMillis = effectiveInterval(item.getSamplingInterval());
        } catch (SnmpValueException error) {
            logger.warn(
                    "Invalid SNMP polling interval device={} host={} port={} op=GET oid={} version={} requestedMs={} category=interval",
                    context.getName(),
                    config.host(),
                    config.port(),
                    address.oid().canonical(),
                    config.version(),
                    item.getSamplingInterval()
            );
            setLocalFailure(item, nodeId, error.statusCode(), error);
            return;
        }

        ActiveSubscription holder = new ActiveSubscription();
        ActiveSubscription displaced = active.put(key, holder);
        if (displaced != null) {
            closeSubscription(key, displaced, "CONCURRENT_REPLACE");
        }
        try {
            PollSubscription subscription = pollHandle.subscribe(
                    address,
                    intervalMillis,
                    raw -> {
                        if (stopped || active.get(key) != holder) {
                            return;
                        }
                        try {
                            DevicePollSnapshot snapshot = pollHandle.snapshot();
                            DataValue value;
                            try {
                                value = codec.toDataValue(address, raw);
                                recordCodecRecovery(nodeId, address);
                            } catch (SnmpValueException error) {
                                value = new DataValue(null, new StatusCode(error.statusCode()),
                                        new DateTime(raw.receivedAt()), DateTime.now());
                                recordCodecFailure(nodeId, address, error);
                                recordLocalFailureSafely(error, "CODEC", nodeId);
                            }
                            latestValues.put(nodeId, value);
                            item.setValue(value);
                            diagnostics.refresh(snapshot);
                            onLegalResponse.run();
                        } catch (RuntimeException error) {
                            recordCallbackFailure("VALUE", item, nodeId, address, error);
                        }
                    },
                    error -> {
                        if (stopped || active.get(key) != holder) {
                            return;
                        }
                        try {
                            DataValue value = diagnostics.qualityFor(
                                    error,
                                    latestValues.get(nodeId)
                            );
                            latestValues.put(nodeId, value);
                            item.setValue(value);
                            diagnostics.refresh(pollHandle.snapshot());
                            notifyDeviceFailure(error, "FAILURE", nodeId);
                        } catch (RuntimeException callbackError) {
                            recordCallbackFailure(
                                    "FAILURE",
                                    item,
                                    nodeId,
                                    address,
                                    callbackError
                            );
                        }
                    }
            );
            holder.attach(subscription);
        } catch (RuntimeException error) {
            active.remove(key, holder);
            SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
            if (failure.unexpected()) {
                logger.error(
                        "SNMP subscription activation failed device={} host={} port={} op=SUBSCRIBE oid={} version={} category={} message={}",
                        context.getName(), config.host(), config.port(), address.oid().canonical(),
                        config.version(), failure.category(), failure.message(), error
                );
            } else {
                logger.warn(
                        "SNMP subscription activation rejected device={} host={} port={} op=SUBSCRIBE oid={} version={} category={} message={}",
                        context.getName(), config.host(), config.port(), address.oid().canonical(),
                        config.version(), failure.category(), failure.message()
                );
            }
            setLocalFailure(item, nodeId, failure.statusCode(), error);
            notifyDeviceFailure(error, "ACTIVATE", nodeId);
        }
    }

    /** TRAP.* paths take no poll slot and ignore the sampling interval: values are pushed when a Notification arrives. */
    private void activateTrapPath(DataItem item, DataItemKey key, NodeId nodeId, TrapItemPath path) {
        ActiveSubscription holder = new ActiveSubscription();
        ActiveSubscription displaced = active.put(key, holder);
        if (displaced != null) {
            closeSubscription(key, displaced, "CONCURRENT_REPLACE");
        }
        Runnable unsubscribe = traps.subscribeTrapPath(path, value -> {
            if (stopped || active.get(key) != holder) {
                return;
            }
            latestValues.put(nodeId, value);
            item.setValue(value);
        });
        holder.attach(unsubscribe::run);
    }

    private void setLocalFailure(
            DataItem item,
            NodeId nodeId,
            long statusCode,
            Throwable error
    ) {
        DataValue value = new DataValue(statusCode);
        latestValues.put(nodeId, value);
        item.setValue(value);
        recordLocalFailureSafely(error, "LOCAL_FAILURE", nodeId);
    }

    private void recordCallbackFailure(
            String callback,
            DataItem item,
            NodeId nodeId,
            ResolvedAddress address,
            RuntimeException error
    ) {
        SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
        DataValue value = new DataValue(failure.statusCode());
        latestValues.put(nodeId, value);
        item.setValue(value);
        logger.error(
                "SNMP subscription callback failed device={} host={} port={} op=GET callback={} oid={} nodeId={} version={} category={} message={}",
                context.getName(), config.host(), config.port(), callback,
                address.oid().canonical(), nodeId, config.version(), failure.category(),
                failure.message(), error
        );
        recordLocalFailureSafely(error, callback, nodeId);
        notifyDeviceFailure(error, callback, nodeId);
    }

    private void notifyDeviceFailure(Throwable error, String source, NodeId nodeId) {
        try {
            onFailure.accept(error);
        } catch (RuntimeException handlerError) {
            SnmpFailureClassifier.Failure failure =
                    SnmpFailureClassifier.classify(handlerError);
            logger.error(
                    "SNMP Device failure handler failed device={} host={} port={} source={} nodeId={} version={} category={} message={}",
                    context.getName(), config.host(), config.port(), source, nodeId,
                    config.version(), failure.category(), failure.message(), handlerError
            );
        }
    }

    private void recordLocalFailureSafely(
            Throwable error,
            String source,
            NodeId nodeId
    ) {
        try {
            diagnostics.recordLocalFailure(error, pollHandle.snapshot());
        } catch (RuntimeException diagnosticsError) {
            SnmpFailureClassifier.Failure failure =
                    SnmpFailureClassifier.classify(diagnosticsError);
            logger.error(
                    "SNMP Diagnostics failure update failed device={} host={} port={} source={} nodeId={} version={} category={} message={}",
                    context.getName(), config.host(), config.port(), source, nodeId,
                    config.version(), failure.category(), failure.message(), diagnosticsError
            );
            notifyDeviceFailure(diagnosticsError, "DIAGNOSTICS_" + source, nodeId);
        }
    }

    private void refreshActivity() {
        DevicePollSnapshot snapshot = pollHandle.snapshot();
        diagnostics.refresh(snapshot);
        onActivityChanged.accept(snapshot);
    }

    private void recordCodecFailure(
            NodeId nodeId,
            ResolvedAddress address,
            SnmpValueException error
    ) {
        long now = System.nanoTime();
        long statusCode = error.statusCode();
        String signature = statusCode + "|" + error.getMessage();
        codecFailures.compute(nodeId, (ignored, existing) -> {
            if (existing == null || !existing.signature.equals(signature)) {
                logger.warn(
                        "SNMP value conversion failed device={} host={} port={} op=GET oid={} version={} category={} statusCode={} message={}",
                        context.getName(), config.host(), config.port(), address.oid().canonical(),
                        config.version(), error.category(), Long.toUnsignedString(statusCode),
                        error.getMessage()
                );
                return new CodecFailureState(signature, error.category(), now, now, 0L);
            }
            existing.suppressed++;
            if (now - existing.lastWarnNanos >= TimeUnit.SECONDS.toNanos(60)) {
                logger.warn(
                        "SNMP repeated value conversion failures device={} host={} port={} op=GET oid={} version={} category={} suppressed={}",
                        context.getName(), config.host(), config.port(), address.oid().canonical(),
                        config.version(), existing.category, existing.suppressed
                );
                existing.lastWarnNanos = now;
                existing.suppressed = 0L;
            }
            return existing;
        });
    }

    private void recordCodecRecovery(NodeId nodeId, ResolvedAddress address) {
        CodecFailureState recovered = codecFailures.remove(nodeId);
        if (recovered != null) {
            logger.info(
                    "SNMP value conversion recovered device={} host={} port={} op=GET oid={} version={} category={} durationMs={}",
                    context.getName(), config.host(), config.port(), address.oid().canonical(),
                    config.version(), recovered.category,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recovered.startedNanos)
            );
        }
    }

    private static long effectiveInterval(double requestedInterval) {
        if (Double.isNaN(requestedInterval) || requestedInterval < 0.0) {
            return 1_000L;
        }
        if (!Double.isFinite(requestedInterval) || requestedInterval < 1_000.0) {
            throw new SnmpValueException(
                    StatusCodes.Bad_OutOfRange,
                    "interval",
                    "INVALID_INTERVAL: SNMP polling interval must be at least 1000 ms"
            );
        }
        if (requestedInterval > Long.MAX_VALUE) {
            throw new SnmpValueException(
                    StatusCodes.Bad_OutOfRange,
                    "interval",
                    "INVALID_INTERVAL: SNMP polling interval is too large"
            );
        }
        return Math.round(requestedInterval);
    }

    private String stripDevicePrefix(NodeId nodeId) {
        Object identifier = nodeId.getIdentifier();
        if (!(identifier instanceof String value)) {
            throw new IllegalArgumentException("SNMP Item Path must use a string NodeId");
        }

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

    private void closeActive(DataItemKey key, String lifecycleEvent) {
        ActiveSubscription existing = active.remove(key);
        if (existing != null) {
            closeSubscription(key, existing, lifecycleEvent);
        }
    }

    private void closeSubscription(
            DataItemKey key,
            ActiveSubscription subscription,
            String lifecycleEvent
    ) {
        try {
            subscription.close();
        } catch (RuntimeException error) {
            SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
            logger.error(
                    "SNMP subscription cleanup failed device={} host={} port={} event={} subscriptionId={} itemId={} version={} category={} message={}",
                    context.getName(), config.host(), config.port(), lifecycleEvent,
                    key.subscriptionId(), key.itemId(),
                    config.version(), failure.category(), failure.message(), error
            );
            recordLocalFailureSafely(error, lifecycleEvent, null);
        }
    }

    private static final class ActiveSubscription {
        private PollSubscription subscription;
        private boolean closed;

        private void attach(PollSubscription next) {
            boolean closeImmediately;
            synchronized (this) {
                closeImmediately = closed;
                if (!closed) {
                    subscription = next;
                }
            }
            if (closeImmediately) {
                next.close();
            }
        }

        private void close() {
            PollSubscription current;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                current = subscription;
                subscription = null;
            }
            if (current != null) {
                current.close();
            }
        }
    }

    private record DataItemKey(UInteger subscriptionId, UInteger itemId) {
        private static DataItemKey of(MonitoredItem item) {
            return new DataItemKey(item.getSubscriptionId(), item.getId());
        }
    }

    private static final class CodecFailureState {
        private final String signature;
        private final String category;
        private final long startedNanos;
        private long lastWarnNanos;
        private long suppressed;

        private CodecFailureState(
                String signature,
                String category,
                long startedNanos,
                long lastWarnNanos,
                long suppressed
        ) {
            this.signature = signature;
            this.category = category;
            this.startedNanos = startedNanos;
            this.lastWarnNanos = lastWarnNanos;
            this.suppressed = suppressed;
        }
    }
}

