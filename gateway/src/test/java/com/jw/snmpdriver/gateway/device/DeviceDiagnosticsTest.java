package com.jw.snmpdriver.gateway.device;

import java.time.Instant;

import com.jw.snmpdriver.snmp.runtime.SnmpNoResponseException;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeviceDiagnosticsTest {
    @Test
    void circuitFailureKeepsLastKnownSourceTime() {
        DeviceDiagnostics diagnostics = new DeviceDiagnostics(null, null);
        Instant receivedAt = Instant.parse("2020-06-15T12:00:00Z");
        DataValue lastKnown = new DataValue(new Variant(42), StatusCode.GOOD,
                new DateTime(receivedAt), new DateTime(receivedAt));

        DataValue degraded = diagnostics.qualityFor(
                new SnmpNoResponseException("circuit open"), lastKnown);

        assertEquals(new StatusCode(StatusCodes.Uncertain_LastUsableValue), degraded.getStatusCode());
        assertEquals(new Variant(42), degraded.getValue());
        assertEquals(new DateTime(receivedAt), degraded.getSourceTime(),
                "Degraded republish must keep the last successful receive instant");
        assertNotNull(degraded.getServerTime());
    }
}
