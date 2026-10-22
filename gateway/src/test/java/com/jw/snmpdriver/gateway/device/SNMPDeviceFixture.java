package com.jw.snmpdriver.gateway.device;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.ImmutableList;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceSubscriptionModel;
import com.jw.snmpdriver.gateway.service.ServiceBundle;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;

/**
 * A real {@link SNMPDevice} and {@link ServiceBundle} on a hand-made Gateway: only the Ignition context
 * interfaces are fake ({@link Proxy}); the SNMP4J listener, registry, router and address space are real.
 * The License starts as requested and can be flipped with {@link #license(boolean)}.
 */
final class SNMPDeviceFixture implements AutoCloseable {
    private static final UShort NAMESPACE = Unsigned.ushort(2);

    final int trapPort;
    final SNMPDevice device;
    private final ServiceBundle bundle = new ServiceBundle();

    /** The Gateway is faked, so the Trap Community is resolved by the device's SecretResolver test seam. */
    SNMPDeviceFixture(boolean trapEnabled, String trapCommunity, boolean licensed) throws Exception {
        trapPort = freeUdpPort();
        GatewayContext gateway = gatewayContext();
        var setup = ServiceBundle.class.getDeclaredMethod("setupProcess", GatewayContext.class);
        setup.setAccessible(true);
        setup.invoke(bundle, gateway);
        bundle.ensureStarted();
        license(licensed);

        OpcUaServer server = new OpcUaServer(OpcUaServerConfig.builder().build(), null);
        // V3 + NO_AUTH_NO_PRIV keeps the polling side free of Secrets
        SNMPDeviceConfig d = SNMPDeviceConfig.defaults();
        SNMPDeviceConfig config = new SNMPDeviceConfig("127.0.0.1", 161, d.ipFamily(), SNMPDeviceConfig.Version.V3,
                null, null, "probe", SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, d.authProtocol(), null,
                d.privacyProtocol(), null, "", d.accessMode(), d.timeoutMillis(), d.retries(), d.maxOidsPerRequest(),
                d.maxRequestBytes(), d.maxInflightPerDevice(), trapEnabled, "127.0.0.1", trapPort,
                d.trapSecurity(), null, "", true, "", 1000);
        device = new SNMPDevice(deviceContext(server, gateway), config, bundle, secret -> trapCommunity);
        device.startup();
    }

    /** Current value of a node under {@code [Device]/Traps/}, e.g. {@code "Last/Json"}. */
    DataValue read(String trapsPath) {
        UaVariableNode node = (UaVariableNode) device.getNodeManager()
                .getNode(new NodeId(NAMESPACE, "Traps/" + trapsPath))
                .orElseThrow(() -> new AssertionError("missing node Traps/" + trapsPath));
        return node.getValue();
    }

    Object value(String trapsPath) {
        return read(trapsPath).getValue().getValue();
    }

    /** Swaps the cached License status that {@code ServiceBundle.licenseVerdict()} reads. */
    @SuppressWarnings("unchecked")
    void license(boolean licensed) throws Exception {
        Field current = com.jw.ignition.license.state.CachedLicenseState.class.getDeclaredField("current");
        current.setAccessible(true);
        LicenseStatusModel status = new LicenseStatusModel();
        status.licensed = licensed;
        status.code = licensed ? LicenseStatusCode.LICENSED : LicenseStatusCode.MISSING_FILE;
        ((AtomicReference<LicenseStatusModel>) current.get(bundle.license().service().cachedState())).set(status);
    }

    @Override
    public void close() {
        device.shutdown();
        bundle.stop();
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static GatewayContext gatewayContext() throws Exception {
        File dataDir = Files.createTempDirectory("snmp-driver-fixture").toFile();
        dataDir.deleteOnExit();
        return proxy(GatewayContext.class, dataDir);
    }

    private static DeviceContext deviceContext(OpcUaServer server, GatewayContext gateway) {
        NodeId root = new NodeId(NAMESPACE, "root");
        return (DeviceContext) Proxy.newProxyInstance(DeviceContext.class.getClassLoader(),
                new Class<?>[] {DeviceContext.class}, (p, m, a) -> switch (m.getName()) {
                    case "getServer" -> server;
                    case "getName" -> "probe";
                    case "getNamespaceIndex" -> NAMESPACE;
                    case "getNamespaceUri" -> "urn:probe";
                    case "getRootNodeId" -> root;
                    case "getDeviceNodeId" -> new NodeId(NAMESPACE, "probe");
                    case "getGatewayContext" -> gateway;
                    case "getSubscriptionModel" -> subscriptionModel();
                    case "nodeId" -> new NodeId(NAMESPACE, String.valueOf(a[0]));
                    case "qualifiedName" -> new QualifiedName(NAMESPACE, (String) a[0]);
                    default -> null;
                });
    }

    private static DeviceSubscriptionModel subscriptionModel() {
        return (DeviceSubscriptionModel) Proxy.newProxyInstance(DeviceSubscriptionModel.class.getClassLoader(),
                new Class<?>[] {DeviceSubscriptionModel.class},
                (p, m, a) -> m.getName().equals("getDataItems") ? ImmutableList.of() : null);
    }

    /** Any interface method answers with a neutral value; interface results are proxies again. */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, File dataDir) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.isDefault()) {
                return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .unreflectSpecial(m, type).bindTo(p).invokeWithArguments(a == null ? new Object[0] : a);
            }
            Class<?> r = m.getReturnType();
            if (m.getName().equals("getDataDir") || r == File.class) {
                return dataDir;
            }
            if (r == String.class) {
                return "";
            }
            if (r == List.class) {
                return List.of();
            }
            if (r == ImmutableList.class) {
                return ImmutableList.of();
            }
            if (r.isInterface() && !r.getName().startsWith("java.")) {
                return proxy(r, dataDir);
            }
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == long.class) {
                return 0L;
            }
            return null;
        });
    }
}
