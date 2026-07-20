package com.jw.ignition.common.license;

import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseAccessSnapshotTest {
    @Test
    void exposesOnlyAccessFields() {
        LicenseStatusModel status = LicenseStatusModel.empty();
        status.licensed = true;
        status.code = LicenseStatusCode.LICENSED;
        status.message = "License is valid.";
        status.verifiedAt = "2026-07-20T00:00:00Z";
        status.expiresAt = "";
        status.features = List.of("report.export");
        status.licensePath = "sensitive/path/license.json";

        LicenseAccessSnapshot snapshot = LicenseAccessSnapshot.from(status);

        assertTrue(snapshot.licensed());
        assertEquals(LicenseStatusCode.LICENSED, snapshot.code());
        assertEquals(List.of("report.export"), snapshot.features());
        assertFalse(snapshot.toString().contains(status.licensePath));
    }

    @Test
    void unavailableStatusFailsClosed() {
        LicenseAccessSnapshot snapshot = LicenseAccessSnapshot.from(null);

        assertFalse(snapshot.licensed());
        assertEquals(List.of(), snapshot.features());
    }
}
