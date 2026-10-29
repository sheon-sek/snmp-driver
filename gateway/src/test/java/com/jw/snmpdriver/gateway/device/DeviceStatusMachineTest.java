package com.jw.snmpdriver.gateway.device;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

import com.jw.snmpdriver.snmp.runtime.SnmpNoResponseException;
import com.jw.snmpdriver.snmp.runtime.SnmpProtocolException;
import com.jw.snmpdriver.snmp.runtime.SnmpTransportUnavailableException;
import com.jw.snmpdriver.snmp.runtime.SnmpValueException;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import com.jw.snmpdriver.snmp.runtime.LicensedSnmpRequestClient;
import com.jw.snmpdriver.snmp.runtime.SnmpRequestClient;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.snmp4j.PDU;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceStatusMachineTest {
    private final List<String> published = new ArrayList<>();
    private final DeviceStatusMachine state =
            new DeviceStatusMachine((previous, next) -> published.add(next));

    private final long generation = state.beginGeneration();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validResponseRecoversRuntimeError(boolean securityFailure) {
        state.failure(generation, securityFailure ? new SecurityException("local security failure")
                : new IllegalArgumentException("local invariant failure"), 1);
        assertEquals("Errored", state.status());

        state.running(generation, 1);

        assertEquals("Running", state.status());
        assertEquals(List.of("Errored", "Running"), published);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runtimeErrorCanBecomeUnlicensedOrDegraded(boolean licenseFailure) {
        state.failure(generation, new IllegalArgumentException("local invariant failure"), 1);
        assertEquals("Errored", state.status());

        state.failure(generation, licenseFailure
                ? new SnmpValueException(StatusCodes.Bad_LicenseExpired, "license", "expired")
                : new SnmpNoResponseException("timeout"), 1);

        assertEquals(licenseFailure ? "Unlicensed" : "Degraded", state.status());
    }

    @Test
    void startupFailureCannotBeHiddenByCallbacks() {
        state.startupFailure(generation);
        state.running(generation, 1);
        state.failure(generation, new SnmpNoResponseException("late timeout"), 1);
        state.activity(generation, 0);
        long replacement = state.beginGeneration();
        state.running(replacement, 1);
        state.failure(replacement,
                new SnmpValueException(StatusCodes.Bad_LicenseExpired, "license", "expired"), 1);

        assertEquals("Errored", state.status());
        assertEquals(List.of("Errored"), published);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retiredCallbacksCannotPublishStatus(boolean shutdown) {
        state.running(generation, 1);
        if (shutdown) {
            state.shutdown();
        } else {
            long replacement = state.beginGeneration();
            state.running(replacement, 1);
        }
        List<String> beforeLateCallbacks = List.copyOf(published);

        state.failure(generation, new IllegalStateException("late error"), 1);
        state.running(generation, 0);
        state.activity(generation, 0);
        state.startupFailure(generation);

        assertEquals("Running", state.status());
        assertEquals(beforeLateCallbacks, published);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void legalProtocolErrorResponseRecoversToIdleOrRunning(int activeOidCount) {
        state.failure(generation, new IllegalStateException("local failure"), activeOidCount);

        state.failure(generation, new SnmpProtocolException(PDU.noSuchName, 1, "missing OID"),
                activeOidCount);

        assertEquals(activeOidCount == 0 ? "Idle" : "Running", state.status());
    }

    @Test
    void successfulResponseWithoutSubscriptionsRecoversToIdle() {
        state.failure(generation, new IllegalStateException("local failure"), 0);

        state.running(generation, 0);

        assertEquals("Idle", state.status());
    }

    @Test
    void resourceFailureDegradesRuntimeErrorAndValidResponseRecovers() {
        state.failure(generation, new IllegalStateException("local failure"), 1);
        state.failure(generation, new RejectedExecutionException("capacity"), 1);
        assertEquals("Degraded", state.status());

        state.running(generation, 1);

        assertEquals("Running", state.status());
    }

    @Test
    void localTransportAbsenceDegradesAndValidResponseRecovers() {
        state.running(generation, 1);
        state.failure(generation, new SnmpTransportUnavailableException("IPv6 transport unavailable"), 1);

        assertEquals("Degraded", state.status());

        state.running(generation, 1);
        assertEquals("Running", state.status());
        assertEquals(List.of("Running", "Degraded", "Running"), published);
    }

    @Test
    void activityConnectsSubscribedDeviceAndEmptyActivityClearsRuntimeError() {
        assertEquals("Starting", state.status());
        state.activity(generation, 0);
        assertEquals("Idle", state.status());
        state.activity(generation, 1);
        assertEquals("Connecting", state.status());
        state.failure(generation, new IllegalStateException("local failure"), 1);
        state.activity(generation, 1);
        assertEquals("Errored", state.status(), "Subscription activity alone is not a response");

        state.activity(generation, 0);

        assertEquals("Idle", state.status());
    }

    @Test
    void cancelledOperationDoesNotHideRuntimeFailure() {
        state.failure(generation, new IllegalStateException("local failure"), 1);
        state.failure(generation, new CancellationException("retired request"), 1);

        assertEquals("Errored", state.status());
        assertEquals(List.of("Errored"), published);
    }

    @Test
    void pollFailureBeforeLicenseIsVerifiedDoesNotShowUnlicensed() {
        state.activity(generation, 1);

        state.failure(generation, pollFailure(LicenseStatusCode.UNVERIFIED), 1);

        assertEquals("Connecting", state.status());
        assertEquals(List.of("Connecting"), published);
    }

    @Test
    void pollFailureWithMissingLicenseStillShowsUnlicensed() {
        state.activity(generation, 1);

        state.failure(generation, pollFailure(LicenseStatusCode.MISSING_FILE), 1);

        assertEquals("Unlicensed", state.status());
    }

    private static Throwable pollFailure(LicenseStatusCode code) {
        LicenseStatusModel status = LicenseStatusModel.empty();
        status.code = code;
        LicensedSnmpRequestClient client = new LicensedSnmpRequestClient(new UnreachableClient(), new LicenseGate(() -> status));
        Throwable[] failure = new Throwable[1];
        client.getBatch(null, List.of()).whenComplete((result, error) -> failure[0] = error);
        return failure[0];
    }

    private static final class UnreachableClient implements SnmpRequestClient {
        @Override
        public java.util.concurrent.CompletionStage<com.jw.snmpdriver.snmp.runtime.BatchResult> getBatch(
                com.jw.snmpdriver.snmp.runtime.SnmpEndpoint endpoint,
                List<com.jw.snmpdriver.snmp.address.NumericOid> oids) {
            throw new AssertionError("License gate must block the request");
        }

        @Override
        public java.util.concurrent.CompletionStage<com.jw.snmpdriver.snmp.runtime.SetResult> set(
                com.jw.snmpdriver.snmp.runtime.SnmpEndpoint endpoint,
                com.jw.snmpdriver.snmp.address.NumericOid oid, org.snmp4j.smi.Variable value) {
            throw new AssertionError("License gate must block the request");
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    }
}
