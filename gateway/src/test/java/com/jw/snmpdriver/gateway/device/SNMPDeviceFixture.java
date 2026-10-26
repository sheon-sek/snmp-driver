package com.jw.snmpdriver.gateway.device;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.ImmutableList;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceSubscriptionModel;
import com.jw.snmpdriver.gateway.service.ServiceBundle;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
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
    private final AtomicInteger nextItemId = new AtomicInteger();

    /** The Gateway is faked, so the Trap Community is resolved by the device's SecretResolver test seam. */
    SNMPDeviceFixture(boolean trapEnabled, String trapCommunity, boolean licensed) throws Exception {
        this(trapEnabled, trapCommunity, licensed, SNMPDeviceConfig.TrapSecurity.COMMUNITY);
    }

    SNMPDeviceFixture(boolean trapEnabled, String trapCommunity, boolean licensed,
                      SNMPDeviceConfig.TrapSecurity trapSecurity) throws Exception {
        this(trapEnabled, trapCommunity, licensed, trapSecurity, "");
    }

    /** {@code trapSenderEngineId} applies to USM: the v3 user "probe" (noAuthNoPriv) is localized to it. */
    SNMPDeviceFixture(boolean trapEnabled, String trapCommunity, boolean licensed,
                      SNMPDeviceConfig.TrapSecurity trapSecurity, String trapSenderEngineId) throws Exception {
        this(trapEnabled, trapCommunity, licensed, trapSecurity, trapSenderEngineId, true);
    }

    /**
     * {@code servicesStarted=false} is a Gateway start: this Device is the first one, so its own startup starts
     * the shared services (and creates the Trap registry) after building its address space.
     */
    SNMPDeviceFixture(boolean trapEnabled, String trapCommunity, boolean licensed,
                      SNMPDeviceConfig.TrapSecurity trapSecurity, String trapSenderEngineId, boolean servicesStarted)
            throws Exception {
        trapPort = freeUdpPort();
        GatewayContext gateway = gatewayContext();
        var setup = ServiceBundle.class.getDeclaredMethod("setupProcess", GatewayContext.class);
        setup.setAccessible(true);
        setup.invoke(bundle, gateway);
        if (servicesStarted) {
            bundle.ensureStarted();
            license(licensed);
        }

        OpcUaServer server = new OpcUaServer(OpcUaServerConfig.builder().build(), null);
        // V3 + NO_AUTH_NO_PRIV keeps the polling side free of Secrets
        SNMPDeviceConfig d = SNMPDeviceConfig.defaults();
        SNMPDeviceConfig config = new SNMPDeviceConfig("127.0.0.1", 161, d.ipFamily(), SNMPDeviceConfig.Version.V3,
                null, null, "probe", SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, d.authProtocol(), null,
                d.privacyProtocol(), null, "", d.accessMode(), d.timeoutMillis(), d.retries(), d.maxOidsPerRequest(),
                d.maxRequestBytes(), d.maxInflightPerDevice(), trapEnabled, "127.0.0.1", trapPort,
                trapSecurity, null, trapSenderEngineId, true, "", 1000);
        device = new SNMPDevice(deviceContext(server, gateway), config, bundle, secret -> trapCommunity);
        device.startup();
        if (!servicesStarted) {
            license(licensed);
        }
    }

    /** A licensed Device with the caller's polling config; {@code secrets} supplies every SecretConfig plaintext. */
    SNMPDeviceFixture(SNMPDeviceConfig config, SNMPDevice.SecretResolver secrets) throws Exception {
        trapPort = config.trapPort();
        GatewayContext gateway = gatewayContext();
        var setup = ServiceBundle.class.getDeclaredMethod("setupProcess", GatewayContext.class);
        setup.setAccessible(true);
        setup.invoke(bundle, gateway);
        bundle.ensureStarted();
        license(true);
        OpcUaServer server = new OpcUaServer(OpcUaServerConfig.builder().build(), null);
        device = new SNMPDevice(deviceContext(server, gateway), config, bundle, secrets);
        device.startup();
    }

    /** Writes the Value attribute of a dynamic Item Path the way the OPC UA server does. */
    StatusCode writePath(String itemPath, Object value) {
        WriteValue write = new WriteValue(new NodeId(NAMESPACE, "[probe]" + itemPath), AttributeId.Value.uid(), null,
                new DataValue(new Variant(value)));
        return device.write(null, List.of(write)).get(0);
    }

    /** Current value of a node under {@code [Device]/Traps/}, e.g. {@code "Last/Json"}. */
    DataValue read(String trapsPath) {
        UaVariableNode node = (UaVariableNode) device.getNodeManager()
                .getNode(new NodeId(NAMESPACE, "Traps/" + trapsPath))
                .orElseThrow(() -> new AssertionError("missing node Traps/" + trapsPath));
        return node.getValue();
    }

    java.nio.file.Path engineIdentityFile() {
        return bundle.context().getSystemManager().getModuleConfigLocalDir("com.jw.snmpdriver")
                .resolve(com.jw.snmpdriver.snmp.trap.TrapEngineIdentity.FILE_NAME);
    }

    Object value(String trapsPath) {
        return read(trapsPath).getValue().getValue();
    }

    /** Reads an attribute of a dynamic Item Path such as {@code TRAP.Count/<oid>} the way the OPC UA server does. */
    DataValue readPath(String itemPath, AttributeId attribute) {
        ReadValueId id = new ReadValueId(new NodeId(NAMESPACE, "[probe]" + itemPath), attribute.uid(), null,
                QualifiedName.NULL_VALUE);
        return device.read(null, 0.0, TimestampsToReturn.Both, List.of(id)).get(0);
    }

    DataValue readPath(String itemPath) {
        return readPath(itemPath, AttributeId.Value);
    }

    /** Creates a monitored item on a dynamic Item Path; every value pushed to it is appended to the result. */
    List<DataValue> subscribePath(String itemPath, double samplingInterval) {
        List<DataValue> published = new CopyOnWriteArrayList<>();
        ReadValueId id = new ReadValueId(new NodeId(NAMESPACE, "[probe]" + itemPath), AttributeId.Value.uid(), null,
                QualifiedName.NULL_VALUE);
        UInteger itemId = Unsigned.uint(nextItemId.incrementAndGet());
        DataItem item = (DataItem) Proxy.newProxyInstance(DataItem.class.getClassLoader(),
                new Class<?>[] {DataItem.class}, (p, m, a) -> switch (m.getName()) {
                    case "getId" -> itemId;
                    case "getSubscriptionId" -> Unsigned.uint(1);
                    case "getReadValueId" -> id;
                    case "getSamplingInterval" -> samplingInterval;
                    case "isSamplingEnabled" -> true;
                    case "setValue" -> {
                        published.add((DataValue) a[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "DataItem#" + itemId;
                    default -> throw new AssertionError("Unexpected DataItem call: " + m.getName());
                });
        device.onDataItemsCreated(List.of(item));
        return published;
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
