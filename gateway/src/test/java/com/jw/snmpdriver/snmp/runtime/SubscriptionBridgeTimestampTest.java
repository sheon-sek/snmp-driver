package com.jw.snmpdriver.snmp.runtime;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.jw.snmpdriver.gateway.device.DeviceDiagnostics;
import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.gateway.device.SubscriptionBridge;
import com.jw.snmpdriver.snmp.address.AddressResolver;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.OctetString;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionBridgeTimestampTest {
    @Test
    void conversionFailureAndCachedRedeliveryKeepReceiveTimeAndPublishBadQuality() {
        ControlledScheduler scheduler = new ControlledScheduler();
        FakeSnmpRequestClient client = new FakeSnmpRequestClient();
        SharedPollCoordinator coordinator = new SharedPollCoordinator(client, scheduler,
                new CallbackExecutor(Runnable::run, 4096), scheduler::nanoTime);
        coordinator.start();
        SNMPDeviceConfig config = SNMPDeviceConfig.defaults();
        SnmpEndpoint endpoint = new SnmpEndpoint("device", "192.0.2.1", 161,
                SNMPDeviceConfig.IpFamily.IPV4, SNMPDeviceConfig.Version.V2C, "", "", "",
                SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256, "",
                SNMPDeviceConfig.PrivacyProtocol.AES128, "", "", SNMPDeviceConfig.AccessMode.READ_ONLY,
                2_000, 0, 25, 1_200, 1);
        DeviceContext context = (DeviceContext) Proxy.newProxyInstance(DeviceContext.class.getClassLoader(),
                new Class<?>[]{DeviceContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "device";
                    throw new AssertionError("Unexpected DeviceContext call: " + method.getName());
                });
        List<DataValue> published = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        NodeId nodeId = new NodeId(1, "[device]Integer32/1.3.6.1.2.1.1.3.0");
        ReadValueId read = new ReadValueId(nodeId, AttributeId.Value.uid(), null, null);
        DataItem item = (DataItem) Proxy.newProxyInstance(DataItem.class.getClassLoader(),
                new Class<?>[]{DataItem.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId", "getSubscriptionId" -> Unsigned.uint(1);
                    case "getReadValueId" -> read;
                    case "getSamplingInterval" -> 1_000d;
                    case "isSamplingEnabled" -> true;
                    case "setValue" -> { published.add((DataValue) args[0]); yield null; }
                    default -> throw new AssertionError("Unexpected DataItem call: " + method.getName());
                });
        SubscriptionBridge bridge = new SubscriptionBridge(context, config, new AddressResolver(),
                coordinator.registerDevice(endpoint), new SnmpValueCodec(), new DeviceDiagnostics(null, context),
                ignored -> false, () -> {}, failures::add, ignored -> {});
        try {
            bridge.created(List.of(item));
            scheduler.runReady();
            assertEquals(1, client.gets.size());
            Instant receivedAt = Instant.parse("2020-06-15T12:00:00Z");
            OctetString incompatible = new OctetString("not-an-integer");
            RawBinding raw = new RawBinding(NumericOid.parse("1.3.6.1.2.1.1.3.0"),
                    incompatible.getSyntax(), incompatible, receivedAt);
            Instant firstPublish = Instant.now();
            client.gets.get(0).result().complete(new BatchResult(List.of(raw), 0, 0, ""));
            assertEquals(1, published.size());
            assertBadReceivedValue(published.get(0), receivedAt, firstPublish);
            assertSame(published.get(0), bridge.latest(nodeId).orElseThrow());
            assertEquals(1, client.gets.size(), "Reading the publish cache must not send GET");

            Instant retryPublish = Instant.now();
            scheduler.advanceBy(1, TimeUnit.SECONDS);
            assertEquals(2, client.gets.size(), "The next request has no replacement response yet");
            assertEquals(2, published.size(), "The coordinator must republish its cached RawBinding");
            assertBadReceivedValue(published.get(1), receivedAt, retryPublish);
            assertSame(published.get(1), bridge.latest(nodeId).orElseThrow());
            assertTrue(failures.isEmpty(), "A conversion failure must retain the existing local quality path");
        } finally {
            bridge.shutdown();
            coordinator.stop();
            scheduler.shutdownNow();
        }
    }

    private static void assertBadReceivedValue(DataValue value, Instant receivedAt, Instant publishStarted) {
        assertEquals(new StatusCode(StatusCodes.Bad_TypeMismatch), value.getStatusCode());
        assertEquals(new DateTime(receivedAt), value.getSourceTime());
        assertTrue(!value.getServerTime().getJavaInstant().isBefore(publishStarted.minusMillis(1)));
        assertTrue(!value.getServerTime().getJavaInstant().isAfter(Instant.now().plusMillis(1)));
    }
}
