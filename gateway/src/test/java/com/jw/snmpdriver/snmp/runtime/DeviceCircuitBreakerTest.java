package com.jw.snmpdriver.snmp.runtime;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceCircuitBreakerTest {
    @Test
    void abortProbeReturnsToOpenWithoutEscalation() {
        DeviceCircuitBreaker breaker = new DeviceCircuitBreaker();
        breaker.onTransportFailure(0, 0);
        breaker.onTransportFailure(0, 0);
        long now = TimeUnit.SECONDS.toNanos(2);
        assertTrue(breaker.tryHalfOpen(now));

        for (int pause = 0; pause < 3; pause++) {
            breaker.abortProbe(now);
            assertEquals(DeviceCircuitBreaker.State.OPEN, breaker.state());
            assertEquals(TimeUnit.SECONDS.toNanos(2), breaker.nanosUntilProbe(now));
            assertFalse(breaker.tryHalfOpen(now + TimeUnit.MILLISECONDS.toNanos(1_999)));
            now += TimeUnit.SECONDS.toNanos(2);
            assertTrue(breaker.tryHalfOpen(now));
        }

        breaker.onTransportFailure(now, 0);
        assertEquals(TimeUnit.SECONDS.toNanos(4), breaker.nanosUntilProbe(now));
        assertTrue(breaker.tryHalfOpen(now + TimeUnit.SECONDS.toNanos(4)));
        breaker.onValidResponse();
        breaker.abortProbe(now);
        assertTrue(breaker.allowNormal(now), "Aborting a completed probe cannot reopen the circuit");
        breaker.onTransportFailure(now, 0);
        assertTrue(breaker.allowNormal(now), "Successful response reset the transport failure count");
        breaker.onTransportFailure(now, 0);
        assertEquals(TimeUnit.SECONDS.toNanos(2), breaker.nanosUntilProbe(now));
    }
}
