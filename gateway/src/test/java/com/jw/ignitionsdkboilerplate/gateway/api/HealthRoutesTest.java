package com.jw.ignitionsdkboilerplate.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthRoutesTest {

    @Test
    void payloadReportsModuleHealthAndVersion() {
        HealthRoutes.HealthPayload payload = new HealthRoutes("1.2.3").payload();

        assertEquals("ok", payload.status());
        assertEquals("1.2.3", payload.version());
    }
}
