package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;

public record DevicePollSnapshot(
        int activeSubscriptionCount,
        int activeOidCount,
        double minimumEffectivePollInterval,
        DeviceCircuitBreaker.State circuitState,
        Instant lastSuccessTime,
        Instant lastFailureTime,
        String lastError
) {
    public static DevicePollSnapshot empty() {
        return new DevicePollSnapshot(
                0, 0, Double.NaN, DeviceCircuitBreaker.State.CLOSED,
                null, null, ""
        );
    }
}

