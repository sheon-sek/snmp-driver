package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import com.jw.snmpdriver.snmp.address.ParsedItemPath;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.address.SnmpWireType;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.snmp4j.smi.Integer32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HalfOpenRecoveryTest {
    private static final NumericOid OID = NumericOid.parse("1.3.6.1.2.1.1.3.0");
    private final ControlledScheduler scheduler = new ControlledScheduler();
    private final FakeSnmpRequestClient client = new FakeSnmpRequestClient();
    private final SharedPollCoordinator coordinator =
            new SharedPollCoordinator(client, scheduler, new CallbackExecutor(Runnable::run, 4096), scheduler::nanoTime);
    private final List<RawBinding> values = new ArrayList<>();

    @AfterEach
    void stop() {
        coordinator.stop();
        scheduler.shutdownNow();
    }

    @Test
    void halfOpenLicenseFailureRecoversActualGetAndValueDelivery() {
        DevicePollHandle handle = registerDevice();
        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        openCircuitWithGets();
        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        assertEquals(3, client.gets.size());
        client.gets.get(2).result().completeExceptionally(
                new SnmpLicenseException("license paused", StatusCodes.Bad_LicenseExpired));

        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        assertEquals(4, client.gets.size(), "License recovery must send a real GET");
        RawBinding recovered = binding();
        client.gets.get(3).result().complete(new BatchResult(List.of(recovered), 0, 0, ""));
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        assertEquals(recovered, values.get(values.size() - 1));
        assertEquals(5, client.gets.size(), "Normal polling resumes after probe success");
    }

    @ParameterizedTest
    @MethodSource("nonNetworkFailures")
    void halfOpenGetRecoversAfterNonNetworkFailure(RuntimeException failure, boolean synchronous) {
        DevicePollHandle handle = registerDevice();
        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        openCircuitWithGets();
        if (synchronous) {
            client.nextGetFailure = failure;
        }
        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        if (!synchronous) {
            client.gets.get(2).result().completeExceptionally(failure);
        }

        scheduler.advanceBy(2, TimeUnit.SECONDS);
        assertEquals(4, client.gets.size());
        client.gets.get(3).result().complete(new BatchResult(List.of(binding()), 0, 0, ""));
        assertEquals(DeviceCircuitBreaker.State.CLOSED, handle.snapshot().circuitState());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedLocalPausesDoNotEscalateTheNextNetworkBackoff(boolean transportUnavailable) {
        DevicePollHandle handle = registerDevice();
        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        openCircuitWithGets();
        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        for (int probe = 2; probe < 5; probe++) {
            client.gets.get(probe).result().completeExceptionally(
                    transportUnavailable
                            ? new SnmpTransportUnavailableException("IPv6 transport unavailable")
                            : new SnmpLicenseException("license paused", StatusCodes.Bad_LicenseExpired));
            scheduler.advanceBy(1_999, TimeUnit.MILLISECONDS);
            assertEquals(probe + 1, client.gets.size());
            scheduler.advanceBy(1, TimeUnit.MILLISECONDS);
            assertEquals(probe + 2, client.gets.size(), "Each local pause retries after 2 s");
        }

        client.gets.get(5).result().completeExceptionally(new SnmpNoResponseException("probe timeout"));
        scheduler.advanceBy(3_999, TimeUnit.MILLISECONDS);
        assertEquals(6, client.gets.size());
        scheduler.advanceBy(501, TimeUnit.MILLISECONDS);
        assertEquals(7, client.gets.size(), "Next network backoff is 4 s plus jitter, not 8 s");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedLocalTransportFailuresDoNotOpenNetworkCircuit(boolean write) {
        DevicePollHandle handle = registerDevice();
        if (!write) {
            handle.subscribe(address(), 1_000, values::add, ignored -> {});
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            var result = write
                    ? handle.set(address(), new Integer32(7),
                            scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(30)).toCompletableFuture()
                    : null;
            scheduler.runReady();
            if (write) {
                assertEquals(attempt + 1, client.sets.size());
                client.sets.get(attempt).result().completeExceptionally(
                        new SnmpTransportUnavailableException("IPv6 transport unavailable"));
                assertTrue(result.isCompletedExceptionally());
            } else {
                assertEquals(attempt + 1, client.gets.size());
                client.gets.get(attempt).result().completeExceptionally(
                        new SnmpTransportUnavailableException("IPv6 transport unavailable"));
            }
            assertEquals(DeviceCircuitBreaker.State.CLOSED, handle.snapshot().circuitState());
            scheduler.advanceBy(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void halfOpenWithoutDispatchableWorkReturnsToOpenAndNewSubscriptionPolls() {
        DevicePollHandle handle = registerDevice(2);
        PollSubscription original = handle.subscribe(address(), 1_000, values::add, ignored -> {});
        scheduler.runReady();
        // Keep the GET pending while two SET failures open the circuit using the other slot.
        for (int attempt = 0; attempt < 2; attempt++) {
            handle.set(address(), new Integer32(7), scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            scheduler.runReady();
            client.sets.get(attempt).result().completeExceptionally(new SnmpNoResponseException("timeout"));
        }
        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        assertEquals(1, client.gets.size(), "The only subscribed OID is already in flight");
        assertEquals(DeviceCircuitBreaker.State.OPEN, handle.snapshot().circuitState(),
                "An empty probe batch must not reserve HALF_OPEN indefinitely");

        original.close();
        client.gets.get(0).result().cancel(false);
        scheduler.advanceBy(10, TimeUnit.SECONDS);
        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        scheduler.runReady();
        assertEquals(2, client.gets.size(), "New subscription recovers after no work remains in flight");
        client.gets.get(1).result().complete(new BatchResult(List.of(binding()), 0, 0, ""));
        assertFalse(values.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("nonNetworkFailures")
    void halfOpenSetRecoversAfterNonNetworkFailure(RuntimeException failure, boolean synchronous) {
        DevicePollHandle handle = registerDevice();
        PollSubscription subscription = handle.subscribe(address(), 1_000, values::add, ignored -> {});
        openCircuitWithGets();
        subscription.close();
        var failed = handle.set(address(), new Integer32(7),
                scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(30)).toCompletableFuture();
        if (synchronous) {
            client.nextSetFailure = failure;
        }
        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        assertEquals(1, client.sets.size());
        if (!synchronous) {
            client.sets.get(0).result().completeExceptionally(failure);
        }
        assertTrue(failed.isCompletedExceptionally());

        var recovered = handle.set(address(), new Integer32(8),
                scheduler.nanoTime() + TimeUnit.SECONDS.toNanos(30)).toCompletableFuture();
        scheduler.advanceBy(2, TimeUnit.SECONDS);
        assertEquals(2, client.sets.size(), "SET probes resume without any GET subscriptions");
        SetResult response = new SetResult(binding());
        client.sets.get(1).result().complete(response);
        assertEquals(response, recovered.join());
        assertEquals(DeviceCircuitBreaker.State.CLOSED, handle.snapshot().circuitState());
        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        scheduler.runReady();
        assertEquals(3, client.gets.size(), "Successful SET probe also restores GET polling");
    }

    @Test
    void subscriptionAddedDuringHalfOpenEventuallyReceivesItsRequestedOid() {
        DevicePollHandle handle = registerDevice();
        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        openCircuitWithGets();
        scheduler.advanceBy(2_500, TimeUnit.MILLISECONDS);
        NumericOid addedOid = NumericOid.parse("1.3.6.1.2.1.1.7.0");
        List<RawBinding> addedValues = new ArrayList<>();
        handle.subscribe(new ResolvedAddress(addedOid, SnmpWireType.INTEGER32,
                ParsedItemPath.Access.READ_WRITE, false, addedOid.canonical()),
                1_000, addedValues::add, ignored -> {});
        scheduler.runReady();
        assertEquals(3, client.gets.size(), "One recovery probe stays in flight");
        client.gets.get(2).result().cancel(false);

        scheduler.advanceBy(2, TimeUnit.SECONDS);
        assertEquals(4, client.gets.size());
        assertEquals(2, client.gets.get(3).oids().size());
        RawBinding added = new RawBinding(addedOid, binding().syntax(), binding().value(), binding().receivedAt());
        client.gets.get(3).result().complete(new BatchResult(List.of(binding(), added), 0, 0, ""));
        assertEquals(List.of(added), addedValues);
        assertEquals(DeviceCircuitBreaker.State.CLOSED, handle.snapshot().circuitState());
    }

    @Test
    void idleOpenDevicePollsWhenSubscriptionsAreAddedLater() {
        DevicePollHandle handle = registerDevice();
        PollSubscription subscription = handle.subscribe(address(), 1_000, values::add, ignored -> {});
        openCircuitWithGets();
        subscription.close();
        scheduler.advanceBy(1, TimeUnit.DAYS);
        assertEquals(2, client.gets.size(), "No GET without subscriptions");

        handle.subscribe(address(), 1_000, values::add, ignored -> {});
        scheduler.runReady();
        assertEquals(3, client.gets.size());
        client.gets.get(2).result().complete(new BatchResult(List.of(binding()), 0, 0, ""));
        assertEquals(List.of(binding()), values);
    }

    private static Stream<Arguments> nonNetworkFailures() {
        return Stream.of(
                new SnmpLicenseException("license paused", StatusCodes.Bad_LicenseExpired),
                new IllegalArgumentException("invalid request configuration"),
                new RejectedExecutionException("resource unavailable"),
                new CompletionException(new SnmpTransportUnavailableException("IPv6 transport unavailable")),
                new CancellationException("request cancelled"),
                new IllegalStateException("local processing failed")
        ).flatMap(failure -> Stream.of(Arguments.of(failure, false), Arguments.of(failure, true)));
    }

    private DevicePollHandle registerDevice() {
        return registerDevice(1);
    }

    private DevicePollHandle registerDevice(int maxInflight) {
        coordinator.start();
        return coordinator.registerDevice(new SnmpEndpoint("device-1", "192.0.2.1", 161,
                SNMPDeviceConfig.IpFamily.IPV4, SNMPDeviceConfig.Version.V2C, "", "", "",
                SNMPDeviceConfig.SecurityLevel.NO_AUTH_NO_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256,
                "", SNMPDeviceConfig.PrivacyProtocol.AES128, "", "",
                SNMPDeviceConfig.AccessMode.READ_WRITE, 2_000, 0, 25, 1_200, maxInflight));
    }

    private void openCircuitWithGets() {
        scheduler.runReady();
        client.gets.get(0).result().completeExceptionally(new SnmpNoResponseException("first timeout"));
        scheduler.advanceBy(1, TimeUnit.SECONDS);
        client.gets.get(1).result().completeExceptionally(new SnmpNoResponseException("second timeout"));
    }

    private static ResolvedAddress address() {
        return new ResolvedAddress(OID, SnmpWireType.INTEGER32, ParsedItemPath.Access.READ_WRITE,
                false, OID.canonical());
    }

    private static RawBinding binding() {
        Integer32 value = new Integer32(42);
        return new RawBinding(OID, value.getSyntax(), value, Instant.parse("2026-10-19T00:00:00Z"));
    }
}
