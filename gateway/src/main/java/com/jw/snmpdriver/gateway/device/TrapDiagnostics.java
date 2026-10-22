package com.jw.snmpdriver.gateway.device;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import com.jw.snmpdriver.snmp.trap.TrapCounters;
import com.jw.snmpdriver.snmp.trap.TrapListenerHandle;
import com.jw.snmpdriver.snmp.trap.TrapListenerState;
import com.jw.snmpdriver.snmp.trap.TrapNotification;
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

/**
 * [Device]/Traps/ diagnostics, counters and Last/* snapshot nodes; independent of the Device's own Status.
 * Values reach the sink as String, Long (UInt32/UInt64), Instant (DateTime, may be null) or a Bad StatusCode.
 */
public final class TrapDiagnostics {
    public static final String FOLDER_ID = "Traps";
    static final String STATUS = "Status";
    static final String LAST_ERROR = "LastError";
    static final String LISTENER_ENDPOINT = "ListenerEndpoint";
    static final String LAST_FOLDER = "Last";
    static final String LAST_SEQUENCE = "Last/Sequence";
    static final String LAST_RECEIVED_TIME = "Last/ReceivedTime";
    static final String LAST_PDU_TYPE = "Last/PduType";
    static final String LAST_VERSION = "Last/Version";
    static final String LAST_TRAP_OID = "Last/TrapOid";
    static final String LAST_SOURCE = "Last/Source";
    static final String LAST_AGENT_ADDRESS = "Last/AgentAddress";
    static final String LAST_AGENT_UP_TIME = "Last/AgentUpTime";
    static final String LAST_VARBINDS = "Last/Varbinds";
    static final String LAST_JSON = "Last/Json";

    private static final String REMEDIATION = "处置建议：Linux 上绑定 1024 以下端口需要 root 或 CAP_NET_BIND_SERVICE，"
            + "建议监听 10162 并用 nftables 将 UDP 162 重定向到 10162；"
            + "Windows 上请检查系统 SNMPTRAP 服务是否占用了 UDP 162（停用该服务或改用其他端口）；"
            + "也可能是端口已被其他进程占用，Gateway 每 30 秒自动重试。";

    private static final String REJECTED_NOTE = "处置建议：修正冲突设备的 Trap 配置或停用冲突设备后，"
            + "重新保存或重新启用本设备以再次申请监听（被拒绝的申请不会自动重试）。";

    private static final String NODE_NOTE = "Last/* 为非原子镜像，需要一致读取时读 Last/Json；"
            + "计数器合并发布，每个节点至多每秒写一次。";

    private static final Logger logger = LoggerFactory.getLogger(TrapDiagnostics.class);

    private enum NodeType {
        STRING(Identifiers.String), UINT32(Identifiers.UInt32), UINT64(Identifiers.UInt64),
        DATE_TIME(Identifiers.DateTime);

        private final NodeId dataType;

        NodeType(NodeId dataType) {
            this.dataType = dataType;
        }
    }

    private static final Map<String, NodeType> NODES = new LinkedHashMap<>();

    static {
        NODES.put(STATUS, NodeType.STRING);
        NODES.put(LAST_ERROR, NodeType.STRING);
        NODES.put(LISTENER_ENDPOINT, NodeType.STRING);
        for (String name : new String[] {"ReceivedCount", "AcceptedCount", "DroppedRateLimitCount",
                "DroppedQueueFullCount", "DroppedUnlicensedCount", "AuthFailureCount",
                "ListenerUnmatchedCount", "ListenerMultiMatchCount"}) {
            NODES.put(name, NodeType.UINT64);
        }
        NODES.put(LAST_SEQUENCE, NodeType.UINT64);
        NODES.put(LAST_RECEIVED_TIME, NodeType.DATE_TIME);
        NODES.put(LAST_PDU_TYPE, NodeType.STRING);
        NODES.put(LAST_VERSION, NodeType.STRING);
        NODES.put(LAST_TRAP_OID, NodeType.STRING);
        NODES.put(LAST_SOURCE, NodeType.STRING);
        NODES.put(LAST_AGENT_ADDRESS, NodeType.STRING);
        NODES.put(LAST_AGENT_UP_TIME, NodeType.UINT32);
        NODES.put(LAST_VARBINDS, NodeType.STRING);
        NODES.put(LAST_JSON, NodeType.STRING);
    }

    private enum Status {
        DISABLED("Disabled"),
        LISTENING("Listening"),
        DEGRADED("Degraded"),
        UNLICENSED("Unlicensed");

        private final String text;

        Status(String text) {
            this.text = text;
        }
    }

    static String describe(String name) {
        return "SNMP Device Trap diagnostic: " + name + "。" + NODE_NOTE;
    }

    private static final String UNLICENSED_NOTE = "SNMP Driver 许可证无效，端口仍在监听但收到的 Notification 被丢弃，"
            + "不更新 Traps 数据；许可证恢复后自动接收，无需重启。";

    private static final String EXPIRED_NOTE = "SNMP Driver 许可证已过期，端口仍在监听但收到的 Notification 被丢弃，"
            + "不更新 Traps 数据；续期后自动接收，无需重启。";

    private final BiConsumer<String, Object> sink;
    private final Supplier<LicenseGate.Verdict> license;
    private final Map<String, LongSupplier> counterSources;
    private final Map<String, Long> publishedCounters = new HashMap<>();
    private final TrapState state = new TrapState();
    private StatusCode lastQuality;
    private TrapListenerHandle handle;
    private boolean nodesCreated;
    private String lastNotice;
    private NodeWriter writer;

    TrapDiagnostics(BiConsumer<String, Object> sink) {
        this(sink, () -> LicenseGate.Verdict.LICENSED);
    }

    TrapDiagnostics(BiConsumer<String, Object> sink, Supplier<LicenseGate.Verdict> license) {
        this(sink, license, Map.of());
    }

    TrapDiagnostics(BiConsumer<String, Object> sink, Supplier<LicenseGate.Verdict> license,
                    Map<String, LongSupplier> counterSources) {
        this.sink = sink;
        this.license = license;
        this.counterSources = counterSources;
    }

    /** Writes to variable nodes created by {@link #createUnder}. */
    public static TrapDiagnostics forNodes(SNMPDevice addressSpace, DeviceContext context,
                                           Supplier<LicenseGate.Verdict> license, TrapCounters counters) {
        NodeWriter writer = new NodeWriter(addressSpace, context);
        Map<String, LongSupplier> sources = new LinkedHashMap<>();
        TrapDiagnostics diagnostics = new TrapDiagnostics(writer::set, license, sources);
        sources.put("ReceivedCount", counters::received);
        sources.put("AcceptedCount", counters::accepted);
        sources.put("DroppedRateLimitCount", counters::droppedRateLimit);
        sources.put("DroppedQueueFullCount", counters::droppedQueueFull);
        sources.put("DroppedUnlicensedCount", counters::droppedUnlicensed);
        sources.put("AuthFailureCount", counters::authFailure);
        sources.put("ListenerUnmatchedCount", () -> diagnostics.listenerCount(false));
        sources.put("ListenerMultiMatchCount", () -> diagnostics.listenerCount(true));
        diagnostics.writer = writer;
        return diagnostics;
    }

    private long listenerCount(boolean multiMatch) {
        TrapListenerHandle current = handle;
        if (current == null) {
            return 0;
        }
        return multiMatch ? current.multiMatchCount() : current.unmatchedCount();
    }

    /** Called on the listener worker for every accepted Notification. */
    public synchronized void accept(TrapNotification notification) {
        TrapState.Snapshot snapshot = state.record(notification);
        if (lastQuality == null) {
            publishLast(snapshot);
        }
    }

    /** Writes the counters whose value changed since the last flush; driven once per second. */
    public synchronized void flushCounters() {
        counterSources.forEach((name, source) -> {
            long value = source.getAsLong();
            if (!Long.valueOf(value).equals(publishedCounters.getOrDefault(name, 0L))) {
                publishedCounters.put(name, value);
                sink.accept(name, value);
            }
        });
    }

    private void publishLast(TrapState.Snapshot snapshot) {
        if (lastQuality != null) {
            for (String name : NODES.keySet()) {
                if (name.startsWith(LAST_FOLDER + "/")) {
                    sink.accept(name, lastQuality);
                }
            }
            return;
        }
        sink.accept(LAST_SEQUENCE, snapshot.sequence());
        sink.accept(LAST_RECEIVED_TIME, snapshot.receivedAt());
        sink.accept(LAST_PDU_TYPE, snapshot.pduType());
        sink.accept(LAST_VERSION, snapshot.version());
        sink.accept(LAST_TRAP_OID, snapshot.trapOid());
        sink.accept(LAST_SOURCE, snapshot.source());
        sink.accept(LAST_AGENT_ADDRESS, snapshot.agentAddress());
        sink.accept(LAST_AGENT_UP_TIME, snapshot.agentUpTime());
        sink.accept(LAST_VARBINDS, snapshot.varbindsJson());
        sink.accept(LAST_JSON, snapshot.json());
    }

    /** Last/* follow the License verdict; null means Good. Only a change rewrites the nodes. */
    private void applyLastQuality(StatusCode quality) {
        if (java.util.Objects.equals(quality, lastQuality)) {
            return;
        }
        lastQuality = quality;
        publishLast(state.last());
    }

    public synchronized void createUnder(UaFolderNode root) {
        if (writer == null || nodesCreated) {
            return;
        }
        writer.createUnder(root);
        nodesCreated = true;
        showDisabled();
    }

    public synchronized void showDisabled() {
        handle = null;
        publish(Status.DISABLED, "", "");
    }

    public synchronized void showUnavailable(String reason) {
        handle = null;
        publish(Status.DEGRADED, reason + " " + REMEDIATION, "");
    }

    /** Follows this handle; later registry changes arrive through {@link #refresh()}. */
    public synchronized void track(TrapListenerHandle tracked) {
        handle = tracked;
        refresh();
    }

    /** The handle was released (shutdown, reconfiguration or startup failure). */
    public synchronized void released() {
        showDisabled();
    }

    public synchronized void refresh() {
        TrapListenerHandle current = handle;
        if (current == null) {
            return;
        }
        if (current.isRejected()) {
            publish(Status.DEGRADED, current.rejection() + " " + REJECTED_NOTE, "");
            return;
        }
        String endpoint = current.endpoint() == null ? "" : current.endpoint();
        switch (current.state()) {
            case BOUND -> {
                // Degraded outranks Unlicensed: without a bound port there is nothing to license
                LicenseGate.Verdict verdict = license.get();
                applyLastQuality(switch (verdict) {
                    case LICENSED -> null;
                    case EXPIRED -> new StatusCode(StatusCodes.Bad_LicenseExpired);
                    case NOT_AVAILABLE -> new StatusCode(StatusCodes.Bad_LicenseNotAvailable);
                });
                if (verdict == LicenseGate.Verdict.LICENSED) {
                    publish(Status.LISTENING, "", endpoint);
                } else {
                    publish(Status.UNLICENSED,
                            verdict == LicenseGate.Verdict.EXPIRED ? EXPIRED_NOTE : UNLICENSED_NOTE, endpoint);
                }
            }
            case FAILED -> publish(Status.DEGRADED, current.lastError() + " " + REMEDIATION, endpoint);
            case CLOSED -> publish(Status.DISABLED, "", "");
        }
    }

    /**
     * Operator-facing hint from the receive path (auth failure, DNS refresh failure). It only shows while
     * Listening and only when the text changes, so a flood of bad packets writes the node once.
     */
    public synchronized void notice(String message) {
        if (handle == null || handle.isRejected() || handle.state() != TrapListenerState.BOUND
                || message.equals(lastNotice)) {
            return;
        }
        lastNotice = message;
        sink.accept(LAST_ERROR, message);
    }

    private void publish(Status status, String error, String endpoint) {
        lastNotice = null;
        if (status != Status.UNLICENSED) {
            applyLastQuality(null);
        }
        sink.accept(STATUS, status.text);
        sink.accept(LAST_ERROR, error);
        sink.accept(LISTENER_ENDPOINT, endpoint);
    }

    private static final class NodeWriter {
        private final SNMPDevice addressSpace;
        private final DeviceContext context;
        private final Map<String, UaVariableNode> nodes = new HashMap<>();

        NodeWriter(SNMPDevice addressSpace, DeviceContext context) {
            this.addressSpace = addressSpace;
            this.context = context;
        }

        void createUnder(UaFolderNode root) {
            UaFolderNode folder = createFolder(root, FOLDER_ID, FOLDER_ID);
            UaFolderNode last = createFolder(folder, FOLDER_ID + "/" + LAST_FOLDER, LAST_FOLDER);
            NODES.forEach((name, type) ->
                    nodes.put(name, createVariable(name.startsWith(LAST_FOLDER + "/") ? last : folder, name, type)));
        }

        private UaFolderNode createFolder(UaFolderNode parent, String id, String name) {
            UaFolderNode folder = new UaFolderNode(
                    addressSpace.getNodeContext(),
                    context.nodeId(id),
                    context.qualifiedName(name),
                    LocalizedText.english(name)
            );
            addressSpace.getNodeManager().addNode(folder);
            parent.addOrganizes(folder);
            return folder;
        }

        private UaVariableNode createVariable(UaFolderNode folder, String name, NodeType type) {
            String browseName = name.substring(name.lastIndexOf('/') + 1);
            UaVariableNode node = UaVariableNode.builder(addressSpace.getNodeContext())
                    .setNodeId(context.nodeId(FOLDER_ID + "/" + name))
                    .setBrowseName(context.qualifiedName(browseName))
                    .setDisplayName(LocalizedText.english(browseName))
                    .setDescription(LocalizedText.english(describe(name)))
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .setDataType(type.dataType)
                    .setValueRank(ValueRanks.Scalar)
                    .setAccessLevel(AccessLevel.READ_ONLY)
                    .setUserAccessLevel(AccessLevel.READ_ONLY)
                    .setValue(new DataValue(initialValue(type)))
                    .build();
            addressSpace.getNodeManager().addNode(node);
            folder.addOrganizes(node);
            return node;
        }

        private static Variant initialValue(NodeType type) {
            return switch (type) {
                case STRING -> new Variant("");
                case UINT32 -> new Variant(Unsigned.uint(0));
                case UINT64 -> new Variant(Unsigned.ulong(0));
                case DATE_TIME -> new Variant(DateTime.NULL_VALUE);
            };
        }

        void set(String name, Object value) {
            UaVariableNode node = nodes.get(name);
            if (node == null) {
                return;
            }
            try {
                node.setValue(toDataValue(NODES.get(name), value));
            } catch (RuntimeException error) {
                logger.warn("Failed to update SNMP Trap diagnostic device={} node={}",
                        context.getName(), name, error);
            }
        }

        private static DataValue toDataValue(NodeType type, Object value) {
            if (value instanceof StatusCode quality) {
                return new DataValue(quality);
            }
            return new DataValue(switch (type) {
                case STRING -> new Variant(value);
                case UINT32 -> new Variant(Unsigned.uint((Long) value & 0xFFFFFFFFL));
                case UINT64 -> new Variant(Unsigned.ulong((Long) value));
                case DATE_TIME -> new Variant(value == null ? DateTime.NULL_VALUE : new DateTime((Instant) value));
            });
        }
    }
}
