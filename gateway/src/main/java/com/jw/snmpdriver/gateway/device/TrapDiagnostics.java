package com.jw.snmpdriver.gateway.device;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import com.jw.snmpdriver.snmp.trap.TrapListenerHandle;
import com.jw.snmpdriver.snmp.trap.TrapListenerState;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.ValueRanks;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** [Device]/Traps/{Status, LastError, ListenerEndpoint}; independent of the Device's own Status. */
public final class TrapDiagnostics {
    public static final String FOLDER_ID = "Traps";
    static final String STATUS = "Status";
    static final String LAST_ERROR = "LastError";
    static final String LISTENER_ENDPOINT = "ListenerEndpoint";

    private static final String REMEDIATION = "处置建议：Linux 上绑定 1024 以下端口需要 root 或 CAP_NET_BIND_SERVICE，"
            + "建议监听 10162 并用 nftables 将 UDP 162 重定向到 10162；"
            + "Windows 上请检查系统 SNMPTRAP 服务是否占用了 UDP 162（停用该服务或改用其他端口）；"
            + "也可能是端口已被其他进程占用，Gateway 每 30 秒自动重试。";

    private static final String REJECTED_NOTE = "处置建议：修正冲突设备的 Trap 配置或停用冲突设备后，"
            + "重新保存或重新启用本设备以再次申请监听（被拒绝的申请不会自动重试）。";

    private static final String M1A_NOTE = "M1a 仅占用端口，尚未处理收到的 Notification";

    private static final Logger logger = LoggerFactory.getLogger(TrapDiagnostics.class);

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
        return "SNMP Device Trap diagnostic: " + name + "。" + M1A_NOTE;
    }

    private static final String UNLICENSED_NOTE = "SNMP Driver 许可证无效，端口仍在监听但收到的 Notification 被丢弃，"
            + "不更新 Traps 数据；许可证恢复后自动接收，无需重启。";

    private static final String EXPIRED_NOTE = "SNMP Driver 许可证已过期，端口仍在监听但收到的 Notification 被丢弃，"
            + "不更新 Traps 数据；续期后自动接收，无需重启。";

    private final BiConsumer<String, String> sink;
    private final Supplier<LicenseGate.Verdict> license;
    private TrapListenerHandle handle;
    private boolean nodesCreated;
    private String lastNotice;
    private NodeWriter writer;

    TrapDiagnostics(BiConsumer<String, String> sink) {
        this(sink, () -> LicenseGate.Verdict.LICENSED);
    }

    TrapDiagnostics(BiConsumer<String, String> sink, Supplier<LicenseGate.Verdict> license) {
        this.sink = sink;
        this.license = license;
    }

    /** Writes to variable nodes created by {@link #createUnder}. */
    public static TrapDiagnostics forNodes(SNMPDevice addressSpace, DeviceContext context,
                                           Supplier<LicenseGate.Verdict> license) {
        NodeWriter writer = new NodeWriter(addressSpace, context);
        TrapDiagnostics diagnostics = new TrapDiagnostics(writer::set, license);
        diagnostics.writer = writer;
        return diagnostics;
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
            UaFolderNode folder = new UaFolderNode(
                    addressSpace.getNodeContext(),
                    context.nodeId(FOLDER_ID),
                    context.qualifiedName(FOLDER_ID),
                    LocalizedText.english(FOLDER_ID)
            );
            addressSpace.getNodeManager().addNode(folder);
            root.addOrganizes(folder);
            for (String name : new String[] {STATUS, LAST_ERROR, LISTENER_ENDPOINT}) {
                nodes.put(name, createVariable(folder, name));
            }
        }

        private UaVariableNode createVariable(UaFolderNode folder, String name) {
            UaVariableNode node = UaVariableNode.builder(addressSpace.getNodeContext())
                    .setNodeId(context.nodeId(FOLDER_ID + "/" + name))
                    .setBrowseName(context.qualifiedName(name))
                    .setDisplayName(LocalizedText.english(name))
                    .setDescription(LocalizedText.english(describe(name)))
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .setDataType(Identifiers.String)
                    .setValueRank(ValueRanks.Scalar)
                    .setAccessLevel(AccessLevel.READ_ONLY)
                    .setUserAccessLevel(AccessLevel.READ_ONLY)
                    .setValue(new DataValue(new Variant("")))
                    .build();
            addressSpace.getNodeManager().addNode(node);
            folder.addOrganizes(node);
            return node;
        }

        void set(String name, String value) {
            UaVariableNode node = nodes.get(name);
            if (node == null) {
                return;
            }
            try {
                node.setValue(new DataValue(new Variant(value)));
            } catch (RuntimeException error) {
                logger.warn("Failed to update SNMP Trap diagnostic device={} node={}",
                        context.getName(), name, error);
            }
        }
    }
}
