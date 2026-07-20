package com.jw.snmpdriver.gateway.device;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.snmp.address.AddressResolver;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.runtime.DevicePollHandle;
import com.jw.snmpdriver.snmp.runtime.SetResult;
import com.jw.snmpdriver.snmp.runtime.SharedPollCoordinator;
import com.jw.snmpdriver.snmp.runtime.SnmpFailureClassifier;
import com.jw.snmpdriver.snmp.runtime.SnmpValueCodec;
import com.jw.snmpdriver.snmp.runtime.SnmpValueException;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;
import org.snmp4j.smi.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DynamicAttributeWriter {
    private static final Logger logger = LoggerFactory.getLogger(DynamicAttributeWriter.class);
    private static final long MAX_QUEUE_WAIT_MILLIS = 30_000L;
    private static final long MAX_COMPLETION_WAIT_MILLIS = 60_000L;

    private final DeviceContext context;
    private final SNMPDeviceConfig config;
    private final AddressResolver resolver;
    private final SnmpValueCodec codec;
    private final DevicePollHandle pollHandle;
    private final DeviceDiagnostics diagnostics;
    private final Predicate<NodeId> isRealNode;
    private final Runnable onLegalResponse;
    private final Consumer<Throwable> onFailure;

    public DynamicAttributeWriter(
            DeviceContext context,
            SNMPDeviceConfig config,
            AddressResolver resolver,
            SnmpValueCodec codec,
            DevicePollHandle pollHandle,
            DeviceDiagnostics diagnostics,
            Predicate<NodeId> isRealNode,
            Runnable onLegalResponse,
            Consumer<Throwable> onFailure
    ) {
        this.context = context;
        this.config = config;
        this.resolver = resolver;
        this.codec = codec;
        this.pollHandle = pollHandle;
        this.diagnostics = diagnostics;
        this.isRealNode = isRealNode;
        this.onLegalResponse = onLegalResponse;
        this.onFailure = onFailure;
    }

    public Optional<StatusCode> write(WriteValue writeValue) {
        NodeId nodeId = writeValue.getNodeId();
        if (!context.getNamespaceIndex().equals(nodeId.getNamespaceIndex())
                || isRealNode.test(nodeId)) {
            return Optional.empty();
        }
        if (!AttributeId.Value.isEqual(writeValue.getAttributeId())) {
            return reject(
                    nodeId,
                    StatusCodes.Bad_AttributeIdInvalid,
                    "attribute",
                    "Dynamic SNMP writes only support the Value attribute",
                    null
            );
        }
        if (writeValue.getIndexRange() != null && !writeValue.getIndexRange().isBlank()) {
            return reject(
                    nodeId,
                    StatusCodes.Bad_IndexRangeInvalid,
                    "index-range",
                    "Dynamic SNMP writes do not support IndexRange",
                    null
            );
        }

        ResolvedAddress address;
        try {
            address = resolver.resolve(stripDevicePrefix(nodeId));
        } catch (IllegalArgumentException error) {
            return reject(
                    nodeId,
                    StatusCodes.Bad_NodeIdInvalid,
                    "item-path",
                    "INVALID_ITEM_PATH: " + error.getMessage(),
                    error
            );
        }

        if (!address.writableCandidate()
                || config.accessMode() != SNMPDeviceConfig.AccessMode.READ_WRITE) {
            String message = !address.writableCandidate()
                    ? "Item Path is read-only; use RW.<Type>/<OID> for a write candidate"
                    : "Device Access Mode is READ_ONLY";
            return reject(nodeId, StatusCodes.Bad_NotWritable, "write-policy", message, null);
        }
        if ((config.version() == SNMPDeviceConfig.Version.V1
                || config.version() == SNMPDeviceConfig.Version.V2C)
                && config.writeCommunity() == null) {
            return reject(
                    nodeId,
                    StatusCodes.Bad_ConfigurationError,
                    "configuration",
                    "Write Community is not configured for this SNMP Device",
                    null
            );
        }

        if (writeValue.getValue() == null || writeValue.getValue().getValue() == null) {
            return reject(
                    nodeId,
                    StatusCodes.Bad_TypeMismatch,
                    "value",
                    "SNMP SET requires a non-null OPC value",
                    null
            );
        }
        Object opcValue = writeValue.getValue().getValue().getValue();
        Variable encoded;
        try {
            encoded = codec.toVariable(address, opcValue);
        } catch (SnmpValueException error) {
            return reject(
                    nodeId,
                    error.statusCode(),
                    error.category(),
                    error.getMessage(),
                    error
            );
        }

        long logicalBudget = saturatedMultiply(config.timeoutMillis(), config.retries() + 1L);
        long callbackGrace = Math.min(1_000L, Math.max(100L, config.timeoutMillis() / 10L));
        long configuredWaitBudget = saturatedAdd(
                saturatedAdd(logicalBudget, logicalBudget),
                callbackGrace
        );
        long waitBudget = Math.min(configuredWaitBudget, MAX_COMPLETION_WAIT_MILLIS);
        long queueWaitBudget = Math.min(logicalBudget, MAX_QUEUE_WAIT_MILLIS);
        long queueDeadline = saturatedAdd(
                System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(queueWaitBudget)
        );

        CompletableFuture<SetResult> future;
        try {
            future = pollHandle
                    .set(address, encoded, queueDeadline)
                    .toCompletableFuture();
        } catch (RuntimeException error) {
            Throwable failure = SnmpFailureClassifier.unwrap(error);
            SnmpFailureClassifier.Failure classified =
                    SnmpFailureClassifier.classify(failure);
            logExecutionFailure(address, failure, classified.unexpected());
            diagnostics.recordLocalFailure(failure, pollHandle.snapshot());
            onFailure.accept(failure);
            return Optional.of(statusFor(failure));
        }
        try {
            future.get(waitBudget, TimeUnit.MILLISECONDS);
            diagnostics.refresh(pollHandle.snapshot());
            onLegalResponse.run();
            return Optional.of(StatusCode.GOOD);
        } catch (TimeoutException error) {
            future.cancel(false);
            SnmpValueException waitFailure = new SnmpValueException(
                    StatusCodes.Bad_Timeout,
                    "write-wait",
                    "SNMP SET completion wait exceeded " + waitBudget
                            + " ms; remote SET outcome is unknown",
                    error
            );
            logger.warn(
                    "SNMP SET completion wait expired device={} host={} port={} oid={} version={} category=write-wait waitMs={} outcome=unknown",
                    context.getName(), config.host(), config.port(), address.oid().canonical(),
                    config.version(), waitBudget
            );
            diagnostics.recordLocalFailure(waitFailure, pollHandle.snapshot());
            return Optional.of(new StatusCode(StatusCodes.Bad_Timeout));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.warn(
                    "SNMP SET interrupted device={} host={} port={} oid={} version={} category=shutdown",
                    context.getName(), config.host(), config.port(), address.oid().canonical(), config.version()
            );
            diagnostics.recordLocalFailure(error, pollHandle.snapshot());
            return Optional.of(new StatusCode(StatusCodes.Bad_Shutdown));
        } catch (ExecutionException | CancellationException error) {
            Throwable failure = error instanceof ExecutionException
                    ? SharedPollCoordinator.unwrap(error.getCause())
                    : error;
            SnmpFailureClassifier.Failure classified = SnmpFailureClassifier.classify(failure);
            diagnostics.recordLocalFailure(failure, pollHandle.snapshot());
            if (classified.legalResponse()) {
                onLegalResponse.run();
            } else {
                onFailure.accept(failure);
            }
            return Optional.of(statusFor(failure));
        }
    }

    private Optional<StatusCode> reject(
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
        logger.warn(
                "SNMP SET rejected device={} host={} port={} nodeId={} version={} category={} message={}",
                context.getName(), config.host(), config.port(), nodeId, config.version(),
                category, message
        );
        diagnostics.recordLocalFailure(failure, pollHandle.snapshot());
        return Optional.of(new StatusCode(statusCode));
    }

    private void logExecutionFailure(
            ResolvedAddress address,
            Throwable failure,
            boolean includeStackTrace
    ) {
        SnmpFailureClassifier.Failure classified = SnmpFailureClassifier.classify(failure);
        String template = "SNMP SET failed device={} host={} port={} oid={} version={} category={} message={}";
        if (includeStackTrace && SnmpFailureClassifier.safeToLogStack(failure)) {
            logger.error(
                    template,
                    context.getName(), config.host(), config.port(), address.oid().canonical(),
                    config.version(), classified.category(), classified.message(), failure
            );
        } else if (includeStackTrace) {
            logger.error(
                    template,
                    context.getName(), config.host(), config.port(), address.oid().canonical(),
                    config.version(), classified.category(), classified.message()
            );
        } else {
            logger.warn(
                    template,
                    context.getName(), config.host(), config.port(), address.oid().canonical(),
                    config.version(), classified.category(), classified.message()
            );
        }
    }

    private StatusCode statusFor(Throwable failure) {
        return new StatusCode(SnmpFailureClassifier.classify(failure).statusCode());
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

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}

