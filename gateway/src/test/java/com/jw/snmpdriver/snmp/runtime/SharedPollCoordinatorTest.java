package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import com.jw.snmpdriver.snmp.address.ParsedItemPath;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.address.SnmpWireType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.snmp4j.smi.Integer32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedPollCoordinatorTest {
    private static final NumericOid OID = NumericOid.parse("1.3.6.1.2.1.1.3.0");
    private final ControlledScheduler scheduler = new ControlledScheduler();
    private final FakeSnmpRequestClient client = new FakeSnmpRequestClient();
    private final SharedPollCoordinator coordinator =
            new SharedPollCoordinator(client, scheduler, scheduler::nanoTime);
    private final List<RawBinding> values = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    @BeforeEach
    void start() {
        coordinator.start();
    }

    @AfterEach
    void stop() {
        coordinator.stop();
        scheduler.shutdownNow();
    }

    @Test
    void callbacksRunOffCompletingThread() throws Exception {
        SharedPollCoordinator async = new SharedPollCoordinator(client, scheduler);
        async.start();
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            DevicePollHandle handle = async.registerDevice(endpoint("192.0.2.1"));
            handle.subscribe(address(OID), 1_000, value -> {
                callbackThread.set(Thread.currentThread());
                delivered.countDown();
            }, failures::add);
            scheduler.runReady();
            Thread receiver = Thread.currentThread();
            client.gets.get(0).result().complete(success(binding(OID, 42)));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertFalse(receiver == callbackThread.get(), "Business callbacks must leave the receiver");
        } finally {
            async.stop();
        }
    }

    @Test
    void runtimeShutdownNotifiesPendingGetAndCancelsPendingSet() {
        DevicePollHandle handle = coordinator.registerDevice(endpoint("192.0.2.1"));
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        scheduler.runReady();
        var set = handle.set(address(OID), new Integer32(7),
                scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(10)).toCompletableFuture();

        coordinator.stop();

        assertEquals(1, failures.size(), "Runtime shutdown must notify the outstanding GET");
        assertTrue(failures.get(0) instanceof CancellationException);
        assertThrows(CancellationException.class, set::join);
        client.gets.get(0).result().complete(success(binding(OID, 42)));
        assertTrue(values.isEmpty());
        assertEquals(1, failures.size());
    }

    @Test
    void closingQueuedDevicesReleasesPoolQueueCapacity() throws Exception {
        CallbackExecutor callbacks = new CallbackExecutor(1, 2);
        SharedPollCoordinator async = new SharedPollCoordinator(client, scheduler, callbacks,
                scheduler::nanoTime);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        async.start();
        try {
            DevicePollHandle blocked = async.registerDevice(endpoint("blocked", "192.0.2.1", 1));
            blocked.subscribe(address(OID), 1_000, value -> {
                entered.countDown();
                await(release);
            }, error -> {});
            scheduler.runReady();
            client.gets.get(0).result().complete(success(binding(OID, 1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++) {
                DevicePollHandle retiring = async.registerDevice(endpoint("retiring", "192.0.2.2", 1));
                retiring.subscribe(address(OID), 1_000, value -> {}, error -> {});
                scheduler.runReady();
                client.gets.get(client.gets.size() - 1).result().complete(success(binding(OID, 2)));
                retiring.close();
            }
            DevicePollHandle current = async.registerDevice(endpoint("current", "192.0.2.3", 1));
            current.subscribe(address(OID), 1_000, value -> delivered.countDown(), error -> {});
            scheduler.runReady();
            client.gets.get(client.gets.size() - 1).result().complete(success(binding(OID, 3)));
            release.countDown();
            assertTrue(delivered.await(2, TimeUnit.SECONDS), "Cancelled lane runners must release pool slots");
        } finally {
            release.countDown();
            async.stop();
            callbacks.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Callback was not released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    @Test
    void subscriptionRequestsAndPublishesValuesAtItsInterval() {
        SnmpEndpoint endpoint = endpoint("192.0.2.1");
        DevicePollHandle handle = coordinator.registerDevice(endpoint);
        handle.subscribe(address(OID), 1_000, values::add, failures::add);

        scheduler.runReady();
        assertEquals(1, client.gets.size());
        assertSame(endpoint, client.gets.get(0).endpoint());
        assertEquals(List.of(OID), client.gets.get(0).oids());
        assertTrue(values.isEmpty(), "No value before the network response");

        RawBinding first = binding(OID, 42);
        client.gets.get(0).result().complete(success(first));
        assertEquals(List.of(first), values);
        assertTrue(failures.isEmpty());

        scheduler.advanceBy(999, TimeUnit.MILLISECONDS);
        assertEquals(1, client.gets.size());
        scheduler.advanceBy(1, TimeUnit.MILLISECONDS);
        assertEquals(2, client.gets.size());
        RawBinding second = binding(OID, 43);
        client.gets.get(1).result().complete(success(second));
        // Cache delivery at the boundary already used this interval's publication.
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(second, values.get(values.size() - 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeCancelsScheduledPollingAndIgnoresLateResponses(boolean requestAlreadySent) {
        DevicePollHandle handle = coordinator.registerDevice(endpoint("192.0.2.1"));
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        if (requestAlreadySent) {
            scheduler.runReady();
            assertEquals(1, client.gets.size());
        }

        handle.close();
        if (requestAlreadySent) {
            client.gets.get(0).result().complete(success(binding(OID, 42)));
        }
        scheduler.advanceBy(1, TimeUnit.DAYS);

        assertEquals(requestAlreadySent ? 1 : 0, client.gets.size());
        assertTrue(values.isEmpty());
        assertTrue(failures.isEmpty());
        assertThrows(CancellationException.class,
                () -> handle.subscribe(address(OID), 1_000, values::add, failures::add));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconfigurePollsNewEndpointAndIgnoresOldCompletion(boolean oldRequestFails) {
        DevicePollHandle oldHandle = coordinator.registerDevice(endpoint("192.0.2.1"));
        oldHandle.subscribe(address(OID), 1_000, values::add, failures::add);
        scheduler.runReady();

        SnmpEndpoint replacement = endpoint("192.0.2.2");
        DevicePollHandle newHandle = coordinator.registerDevice(replacement);
        newHandle.subscribe(address(OID), 1_000, values::add, failures::add);
        // Closing the retired lease must not unregister its replacement.
        oldHandle.close();
        if (oldRequestFails) {
            client.gets.get(0).result().completeExceptionally(new SnmpNoResponseException("late timeout"));
        } else {
            client.gets.get(0).result().complete(success(binding(OID, 41)));
        }
        scheduler.runReady();

        assertEquals(2, client.gets.size());
        assertSame(replacement, client.gets.get(1).endpoint());
        assertEquals(List.of(OID), client.gets.get(1).oids());
        assertTrue(values.isEmpty());
        assertTrue(failures.isEmpty());

        RawBinding current = binding(OID, 42);
        client.gets.get(1).result().complete(success(current));
        assertEquals(List.of(current), values);
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(3, client.gets.size());
        assertSame(replacement, client.gets.get(2).endpoint());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRequestPublishesFailureAndNextPollCanPublishValue(boolean synchronous) {
        DevicePollHandle handle = coordinator.registerDevice(endpoint("192.0.2.1"));
        IllegalArgumentException error = new IllegalArgumentException("invalid request configuration");
        if (synchronous) {
            client.nextGetFailure = error;
        }
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        scheduler.runReady();
        if (!synchronous) {
            client.gets.get(0).result().completeExceptionally(error);
        }

        assertEquals(List.of(error), failures);
        assertTrue(values.isEmpty());
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(2, client.gets.size());
        assertEquals(List.of(OID), client.gets.get(1).oids());

        RawBinding recovered = binding(OID, 42);
        client.gets.get(1).result().complete(success(recovered));
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(List.of(recovered), values);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeOrReconfigureCancelsQueuedSetWithoutSendingIt(boolean reconfigure) {
        DevicePollHandle handle = coordinator.registerDevice(endpoint("192.0.2.1"));
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        scheduler.runReady();
        // A GET occupies the single in-flight slot, leaving this SET queued.
        var result = handle.set(address(OID), new Integer32(7),
                scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(10)).toCompletableFuture();
        scheduler.runReady();
        assertFalse(result.isDone());
        assertTrue(client.sets.isEmpty());

        if (reconfigure) {
            coordinator.registerDevice(endpoint("192.0.2.2"));
        } else {
            handle.close();
        }
        assertTrue(result.isDone(), "Queued SET must complete when its generation closes");
        assertThrows(CancellationException.class, result::join);
        client.gets.get(0).result().complete(success(binding(OID, 42)));
        scheduler.advanceBy(1, TimeUnit.DAYS);
        assertEquals(1, client.gets.size());
        assertTrue(client.sets.isEmpty());
        assertTrue(values.isEmpty());
        assertTrue(failures.isEmpty());
    }

    @Test
    void transportFailuresBackOffThenAProbeRestoresValueDelivery() {
        DevicePollHandle handle = coordinator.registerDevice(endpoint("192.0.2.1"));
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        scheduler.runReady();
        client.gets.get(0).result().completeExceptionally(new SnmpNoResponseException("first timeout"));
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        client.gets.get(1).result().completeExceptionally(new SnmpNoResponseException("second timeout"));

        scheduler.advanceBy(1_999, TimeUnit.MILLISECONDS);
        assertEquals(2, client.gets.size(), "No network request during the initial 2 s backoff");
        // The existing breaker adds 0..500 ms jitter; advance through its full range.
        scheduler.advanceBy(501, TimeUnit.MILLISECONDS);
        assertEquals(3, client.gets.size(), "A real GET must be sent as the recovery probe");
        assertEquals(List.of(OID), client.gets.get(2).oids());

        RawBinding recovered = binding(OID, 42);
        client.gets.get(2).result().complete(success(recovered));
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(List.of(recovered), values);
        assertEquals(4, client.gets.size(), "Normal polling continues after the successful probe");
    }

    @Test
    void subscriptionsShareRequestsAndReceiveOnlyTheirRequestedOid() {
        NumericOid otherOid = NumericOid.parse("1.3.6.1.2.1.1.7.0");
        List<RawBinding> sameOidValues = new ArrayList<>();
        List<RawBinding> otherValues = new ArrayList<>();
        DevicePollHandle handle = coordinator.registerDevice(endpoint("192.0.2.1"));
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        handle.subscribe(address(OID), 1_000, sameOidValues::add, failures::add);
        handle.subscribe(address(otherOid), 1_000, otherValues::add, failures::add);
        scheduler.runReady();

        assertEquals(1, client.gets.size());
        assertEquals(2, client.gets.get(0).oids().size());
        assertEquals(Set.of(OID, otherOid), Set.copyOf(client.gets.get(0).oids()));
        RawBinding first = binding(OID, 42);
        RawBinding second = binding(otherOid, 7);
        // Response binding order need not match request order.
        client.gets.get(0).result().complete(success(second, first));

        assertEquals(List.of(first), values);
        assertEquals(List.of(first), sameOidValues);
        assertEquals(List.of(second), otherValues);
        assertTrue(failures.isEmpty());
    }

    @Test
    void setSendsRequestedValueAndCompletesWithTheResponse() {
        SnmpEndpoint endpoint = endpoint("192.0.2.1");
        DevicePollHandle handle = coordinator.registerDevice(endpoint);
        var result = handle.set(address(OID), new Integer32(7),
                scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(10)).toCompletableFuture();
        scheduler.runReady();

        assertEquals(1, client.sets.size());
        assertSame(endpoint, client.sets.get(0).endpoint());
        assertEquals(OID, client.sets.get(0).oid());
        assertEquals(new Integer32(7), client.sets.get(0).value());
        assertFalse(result.isDone());

        SetResult response = new SetResult(binding(OID, 7));
        client.sets.get(0).result().complete(response);
        assertTrue(result.isDone(), "SET completion must reach the caller");
        assertEquals(response, result.join());
        assertTrue(client.gets.isEmpty());
    }

    @Test
    void cachedRedeliveryKeepsBindingInstant() {
        SnmpEndpoint endpoint = endpoint("192.0.2.1");
        DevicePollHandle handle = coordinator.registerDevice(endpoint);
        handle.subscribe(address(OID), 1_000, values::add, failures::add);
        scheduler.runReady();
        assertEquals(1, client.gets.size());

        Instant receivedAt = Instant.parse("2020-06-15T12:00:00Z");
        RawBinding first = binding(OID, 42, receivedAt);
        client.gets.get(0).result().complete(success(first));
        assertEquals(List.of(first), values);

        // The interval boundary republishes the cached binding before its
        // replacement response exists, so the receive instant must not move.
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(2, client.gets.size());
        assertEquals(2, values.size());
        assertEquals(receivedAt, values.get(1).receivedAt(),
                "Cached redelivery must keep the original receive instant");
        assertTrue(failures.isEmpty());
    }

    private static RawBinding binding(NumericOid oid, int value, Instant receivedAt) {
        Integer32 variable = new Integer32(value);
        return new RawBinding(oid, variable.getSyntax(), variable, receivedAt);
    }


    private static SnmpEndpoint endpoint(String host) {
        return endpoint("device-1", host, 1);
    }

    private static SnmpEndpoint endpoint(String deviceId, String host, int maxInflight) {
        return new SnmpEndpoint(deviceId, host, 161, SNMPDeviceConfig.IpFamily.IPV4,
                SNMPDeviceConfig.Version.V2C, "", "", "",
                SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256,
                "", SNMPDeviceConfig.PrivacyProtocol.AES128, "", "",
                SNMPDeviceConfig.AccessMode.READ_WRITE, 2_000, 0, 25, 1_200, maxInflight);
    }

    private static ResolvedAddress address(NumericOid oid) {
        return new ResolvedAddress(oid, SnmpWireType.INTEGER32, ParsedItemPath.Access.READ_WRITE,
                false, oid.canonical());
    }

    private static RawBinding binding(NumericOid oid, int value) {
        return binding(oid, value, Instant.parse("2026-10-19T00:00:00Z"));
    }

    private static BatchResult success(RawBinding... bindings) {
        return new BatchResult(List.of(bindings), 0, 0, "");
    }
}
