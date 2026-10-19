package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import com.jw.snmpdriver.snmp.address.ParsedItemPath;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.address.SnmpWireType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.snmp4j.smi.Integer32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observes dispatch through the approved fake-client and controlled-scheduler boundary. */
class CallbackDispatchTest {
    private static final NumericOid OID = NumericOid.parse("1.3.6.1.2.1.1.3.0");
    private final ControlledScheduler scheduler = new ControlledScheduler();
    private final ControlledScheduler callbackScheduler = new ControlledScheduler();
    private final FakeSnmpRequestClient client = new FakeSnmpRequestClient();
    private final List<RawBinding> values = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private CallbackExecutor callbacks;
    private SharedPollCoordinator coordinator;

    private void start(int capacity) {
        callbacks = new CallbackExecutor(callbackScheduler, capacity);
        coordinator = new SharedPollCoordinator(client, scheduler, callbacks, scheduler::nanoTime);
        coordinator.start();
    }

    @AfterEach
    void stop() {
        if (coordinator != null) {
            coordinator.stop();
            callbacks.shutdownNow();
        }
        scheduler.shutdownNow();
        callbackScheduler.shutdownNow();
    }

    @Test
    void callbackAdmissionRejectionNotifiesResourceFailureAndClosingReleasesCapacity() {
        start(1);
        DevicePollHandle first = coordinator.registerDevice(endpoint("first", 1));
        first.subscribe(address(), 1_000, value -> {}, error -> {});
        scheduler.runReady();
        DevicePollHandle overloaded = coordinator.registerDevice(endpoint("overloaded", 1));
        overloaded.subscribe(address(), 1_000, values::add, failures::add);
        scheduler.runReady();

        assertEquals(1, client.gets.size(), "Overload is rejected before occupying transport resources");
        assertEquals(1, failures.size());
        assertEquals("resource", SnmpFailureClassifier.classify(failures.get(0)).category());
        assertTrue(overloaded.snapshot().lastError().contains("CallbackRejectedCount=1"));
        var rejectedSet = overloaded.set(address(), new Integer32(7), deadline()).toCompletableFuture();
        scheduler.runReady();
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, rejectedSet::join).getCause());
        assertTrue(client.sets.isEmpty());

        first.close();
        overloaded.close();
        DevicePollHandle recovered = coordinator.registerDevice(endpoint("recovered", 1));
        recovered.subscribe(address(), 1_000, values::add, failures::add);
        scheduler.runReady();
        assertEquals(2, client.gets.size());
        RawBinding response = binding(42);
        client.gets.get(1).result().complete(success(response));
        assertTrue(values.isEmpty(), "Receiver only enqueues the completion");
        callbackScheduler.runReady();
        assertEquals(List.of(response), values);
        // A retired response cannot consume the new generation's callback capacity.
        client.gets.get(0).result().complete(success(binding(1)));
        callbackScheduler.runReady();
        assertEquals(List.of(response), values);
    }

    @Test
    void mixedGetAndSetCallbacksFollowCompletionOrderWithinDevice() {
        start(8);
        List<String> order = new ArrayList<>();
        DevicePollHandle device = coordinator.registerDevice(endpoint("device", 3));
        device.subscribe(address(), 1_000, value -> order.add("get"), failures::add);
        scheduler.runReady();
        var firstSet = device.set(address(), new Integer32(1), deadline());
        var secondSet = device.set(address(), new Integer32(2), deadline());
        firstSet.thenRun(() -> order.add("set-1"));
        secondSet.thenRun(() -> order.add("set-2"));
        scheduler.runReady();
        assertEquals(2, client.sets.size());

        client.sets.get(1).result().complete(new SetResult(binding(2)));
        client.gets.get(0).result().complete(success(binding(42)));
        client.sets.get(0).result().complete(new SetResult(binding(1)));
        assertTrue(order.isEmpty());
        callbackScheduler.runReady();

        assertEquals(List.of("set-2", "get", "set-1"), order);
        assertTrue(failures.isEmpty());
    }

    @Test
    void unexpectedResponseFailureDoesNotStrandFollowingCompletionOrInflightBudget() {
        start(4);
        DevicePollHandle device = coordinator.registerDevice(endpoint("device", 2));
        device.subscribe(address(), 1_000, values::add, failures::add);
        scheduler.runReady();
        var set = device.set(address(), new Integer32(1), deadline()).toCompletableFuture();
        scheduler.runReady();
        client.gets.get(0).result().complete(null);
        client.sets.get(0).result().complete(new SetResult(binding(1)));
        callbackScheduler.runReady();

        assertEquals(1, failures.size());
        assertEquals(1, set.join().binding().value().toInt());
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(2, client.gets.size(), "GET can retry after callback processing fails");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackShutdownFailsWaitingAndQueuedWorkAndIgnoresLateResponses(boolean immediate) {
        start(8);
        DevicePollHandle device = coordinator.registerDevice(endpoint("device", 3));
        device.subscribe(address(), 1_000, values::add, failures::add);
        scheduler.runReady();
        var queuedCompletion = device.set(address(), new Integer32(1), deadline()).toCompletableFuture();
        var waitingResponse = device.set(address(), new Integer32(2), deadline()).toCompletableFuture();
        scheduler.runReady();
        client.sets.get(0).result().complete(new SetResult(binding(1)));
        assertFalse(queuedCompletion.isDone());

        if (immediate) {
            callbacks.shutdownNow();
        } else {
            callbacks.shutdown();
        }

        assertThrows(CancellationException.class, queuedCompletion::join);
        assertThrows(CancellationException.class, waitingResponse::join);
        assertEquals(1, failures.size());
        assertEquals("shutdown", SnmpFailureClassifier.classify(failures.get(0)).category());
        client.gets.get(0).result().complete(success(binding(42)));
        client.sets.get(1).result().complete(new SetResult(binding(2)));
        callbackScheduler.runReady();
        assertTrue(values.isEmpty());
        assertEquals(1, failures.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retiredGenerationCancelsInflightSetsAndQueuedResponsesBeforeReusingBudget(boolean reconfigure) {
        start(3);
        DevicePollHandle old = coordinator.registerDevice(endpoint("device", 3));
        old.subscribe(address(), 1_000, values::add, failures::add);
        var queued = old.set(address(), new Integer32(1), deadline()).toCompletableFuture();
        var waiting = old.set(address(), new Integer32(2), deadline()).toCompletableFuture();
        scheduler.runReady();
        client.sets.get(0).result().complete(new SetResult(binding(1)));
        client.gets.get(0).result().complete(success(binding(1)));
        assertFalse(queued.isDone());

        if (!reconfigure) {
            old.close();
        }
        DevicePollHandle replacement = coordinator.registerDevice(endpoint("device", 1));
        replacement.subscribe(address(), 1_000, values::add, failures::add);
        old.close();
        assertThrows(CancellationException.class, queued::join);
        assertThrows(CancellationException.class, waiting::join);
        scheduler.runReady();
        assertEquals(2, client.gets.size(), "Old generation must release all callback reservations");
        client.sets.get(1).result().complete(new SetResult(binding(2)));
        RawBinding current = binding(42);
        client.gets.get(1).result().complete(success(current));
        callbackScheduler.runReady();
        assertEquals(List.of(current), values);
        assertTrue(failures.isEmpty(), "Old generation must not publish after close or replacement");
    }

    @Test
    void blockedDeviceDoesNotBlockReceiverOrOtherDevices() throws Exception {
        callbacks = new CallbackExecutor();
        coordinator = new SharedPollCoordinator(client, scheduler, callbacks, scheduler::nanoTime);
        coordinator.start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch receiverReturned = new CountDownLatch(1);
        CountDownLatch otherDelivered = new CountDownLatch(1);
        CountDownLatch setDelivered = new CountDownLatch(1);
        DevicePollHandle blocked = coordinator.registerDevice(endpoint("blocked", 2));
        blocked.subscribe(address(), 1_000, value -> {
            entered.countDown();
            await(release);
        }, error -> {});
        DevicePollHandle other = coordinator.registerDevice(endpoint("other", 1));
        other.subscribe(address(), 1_000, value -> otherDelivered.countDown(), error -> {});
        var set = blocked.set(address(), new Integer32(7), deadline());
        set.thenRun(setDelivered::countDown);
        scheduler.runReady();
        try {
            var blockedGet = client.gets.stream()
                    .filter(request -> request.endpoint().deviceId().equals("blocked")).findFirst().orElseThrow();
            var otherGet = client.gets.stream()
                    .filter(request -> request.endpoint().deviceId().equals("other")).findFirst().orElseThrow();
            blockedGet.result().complete(success(binding(1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread receiver = new Thread(() -> {
                client.sets.get(0).result().complete(new SetResult(binding(7)));
                otherGet.result().complete(success(binding(2)));
                receiverReturned.countDown();
            }, "fake-snmp-receiver");
            receiver.start();
            assertTrue(receiverReturned.await(2, TimeUnit.SECONDS), "Receiver must not acquire the Device lock");
            assertTrue(otherDelivered.await(2, TimeUnit.SECONDS), "Devices must process responses in parallel");
            assertEquals(1, setDelivered.getCount(), "Same Device must not overlap callbacks");
            release.countDown();
            assertTrue(setDelivered.await(2, TimeUnit.SECONDS));
            receiver.join(2_000);
        } finally {
            release.countDown();
        }
    }

    private long deadline() {
        return scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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

    private static SnmpEndpoint endpoint(String id, int maxInflight) {
        return new SnmpEndpoint(id, "192.0.2.1", 161, SNMPDeviceConfig.IpFamily.IPV4,
                SNMPDeviceConfig.Version.V2C, "", "", "",
                SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256,
                "", SNMPDeviceConfig.PrivacyProtocol.AES128, "", "",
                SNMPDeviceConfig.AccessMode.READ_WRITE, 2_000, 0, 25, 1_200, maxInflight);
    }

    private static ResolvedAddress address() {
        return new ResolvedAddress(OID, SnmpWireType.INTEGER32, ParsedItemPath.Access.READ_WRITE,
                false, OID.canonical());
    }

    private static RawBinding binding(int value) {
        Integer32 variable = new Integer32(value);
        return new RawBinding(OID, variable.getSyntax(), variable, Instant.parse("2026-10-19T00:00:00Z"));
    }

    private static BatchResult success(RawBinding binding) {
        return new BatchResult(List.of(binding), 0, 0, "");
    }
}
