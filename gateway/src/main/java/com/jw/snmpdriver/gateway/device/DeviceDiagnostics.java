package com.jw.snmpdriver.gateway.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.snmp.runtime.DevicePollSnapshot;
import com.jw.snmpdriver.snmp.runtime.SnmpFailureClassifier;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.ValueRanks;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeviceDiagnostics {
    public static final String FOLDER_ID = "Diagnostics";
    private static final Logger logger = LoggerFactory.getLogger(DeviceDiagnostics.class);
    private static final long FAILURE_SUMMARY_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final SNMPDevice addressSpace;
    private final DeviceContext context;
    private final List<UaVariableNode> variables = new ArrayList<>();
    private final Map<NodeId, DiagnosticFailureState> updateFailures = new ConcurrentHashMap<>();

    private UaVariableNode status;
    private UaVariableNode activeSubscriptionCount;
    private UaVariableNode activeOidCount;
    private UaVariableNode minimumEffectivePollInterval;
    private UaVariableNode circuitBreakerState;
    private UaVariableNode lastSuccessTime;
    private UaVariableNode lastFailureTime;
    private UaVariableNode lastError;
    private Instant lastRecordedFailureTime;

    public DeviceDiagnostics(SNMPDevice addressSpace, DeviceContext context) {
        this.addressSpace = addressSpace;
        this.context = context;
    }

    public synchronized void createUnder(UaFolderNode root) {
        if (!variables.isEmpty()) {
            return;
        }

        UaFolderNode folder = new UaFolderNode(
                addressSpace.getNodeContext(),
                context.nodeId(FOLDER_ID),
                context.qualifiedName(FOLDER_ID),
                LocalizedText.english(FOLDER_ID)
        );
        addressSpace.getNodeManager().addNode(folder);
        root.addOrganizes(folder);

        status = createVariable(folder, "Status", Identifiers.String, "Starting");
        activeSubscriptionCount = createVariable(
                folder, "ActiveSubscriptionCount", Identifiers.UInt32, Unsigned.uint(0));
        activeOidCount = createVariable(
                folder, "ActiveOidCount", Identifiers.UInt32, Unsigned.uint(0));
        minimumEffectivePollInterval = createVariable(
                folder, "MinimumEffectivePollInterval", Identifiers.Double, Double.NaN);
        circuitBreakerState = createVariable(
                folder, "CircuitBreakerState", Identifiers.String, "CLOSED");
        lastSuccessTime = createVariable(
                folder, "LastSuccessTime", Identifiers.DateTime, DateTime.NULL_VALUE);
        lastFailureTime = createVariable(
                folder, "LastFailureTime", Identifiers.DateTime, DateTime.NULL_VALUE);
        lastError = createVariable(folder, "LastError", Identifiers.String, "");
    }

    public synchronized void updateStatus(String value) {
        set(status, value);
    }

    public synchronized void refresh(DevicePollSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        set(activeSubscriptionCount, Unsigned.uint(snapshot.activeSubscriptionCount()));
        set(activeOidCount, Unsigned.uint(snapshot.activeOidCount()));
        set(minimumEffectivePollInterval, snapshot.minimumEffectivePollInterval());
        set(circuitBreakerState, snapshot.circuitState().name());
        if (snapshot.lastSuccessTime() != null) {
            set(lastSuccessTime, new DateTime(snapshot.lastSuccessTime()));
        }
        if (snapshot.lastFailureTime() != null
                && (lastRecordedFailureTime == null
                || !snapshot.lastFailureTime().isBefore(lastRecordedFailureTime))) {
            lastRecordedFailureTime = snapshot.lastFailureTime();
            set(lastFailureTime, new DateTime(snapshot.lastFailureTime()));
            if (snapshot.lastError() != null && !snapshot.lastError().isBlank()) {
                set(lastError, snapshot.lastError());
            }
        }
    }

    public synchronized void recordLocalFailure(
            Throwable error,
            DevicePollSnapshot snapshot
    ) {
        refresh(snapshot);
        Instant now = Instant.now();
        lastRecordedFailureTime = now;
        set(lastFailureTime, new DateTime(now));
        set(lastError, SnmpFailureClassifier.classify(error).diagnosticMessage());
    }

    public DataValue qualityFor(Throwable error, DataValue lastKnown) {
        SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
        if (failure.circuitFailure()
                && lastKnown != null
                && lastKnown.getValue() != null
                && lastKnown.getValue().getValue() != null) {
            return new DataValue(
                    lastKnown.getValue(),
                    new StatusCode(StatusCodes.Uncertain_LastUsableValue),
                    lastKnown.getSourceTime(),
                    DateTime.now()
            );
        }
        return new DataValue(failure.statusCode());
    }

    private UaVariableNode createVariable(
            UaFolderNode folder,
            String name,
            NodeId dataType,
            Object initialValue
    ) {
        UaVariableNode node = UaVariableNode.builder(addressSpace.getNodeContext())
                .setNodeId(context.nodeId(FOLDER_ID + "/" + name))
                .setBrowseName(context.qualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
                .setDescription(LocalizedText.english("SNMP Device diagnostic: " + name))
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setDataType(dataType)
                .setValueRank(ValueRanks.Scalar)
                .setAccessLevel(AccessLevel.READ_ONLY)
                .setUserAccessLevel(AccessLevel.READ_ONLY)
                .setValue(value(initialValue))
                .build();
        addressSpace.getNodeManager().addNode(node);
        folder.addOrganizes(node);
        variables.add(node);
        return node;
    }

    private void set(UaVariableNode node, Object value) {
        if (node != null) {
            NodeId nodeId = node.getNodeId();
            try {
                node.setValue(value(value));
                DiagnosticFailureState recovered = updateFailures.remove(nodeId);
                if (recovered != null) {
                    logger.info(
                            "SNMP diagnostic update recovered device={} nodeId={} durationMs={}",
                            context.getName(), nodeId,
                            TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - recovered.startedNanos
                            )
                    );
                }
            } catch (RuntimeException error) {
                long now = System.nanoTime();
                updateFailures.compute(nodeId, (ignored, existing) -> {
                    if (existing == null) {
                        logger.error(
                                "Failed to update SNMP diagnostic device={} nodeId={} category=diagnostics",
                                context.getName(), nodeId, error
                        );
                        return new DiagnosticFailureState(now, now, 0L);
                    }
                    existing.suppressed++;
                    if (now - existing.lastWarnNanos >= FAILURE_SUMMARY_NANOS) {
                        logger.error(
                                "Repeated SNMP diagnostic update failures device={} nodeId={} category=diagnostics suppressed={}",
                                context.getName(), nodeId, existing.suppressed
                        );
                        existing.lastWarnNanos = now;
                        existing.suppressed = 0L;
                    }
                    return existing;
                });
            }
        }
    }

    private static DataValue value(Object value) {
        return new DataValue(new Variant(value));
    }

    private static final class DiagnosticFailureState {
        private final long startedNanos;
        private long lastWarnNanos;
        private long suppressed;

        private DiagnosticFailureState(
                long startedNanos,
                long lastWarnNanos,
                long suppressed
        ) {
            this.startedNanos = startedNanos;
            this.lastWarnNanos = lastWarnNanos;
            this.suppressed = suppressed;
        }
    }
}

