package com.jw.ignition.common.license;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LicenseRequirementTest {
    @Test
    void licensedRequirementHasNoFeatures() {
        LicenseRequirement requirement = LicenseRequirement.licensed();

        assertEquals(LicenseRequirement.Match.LICENSED, requirement.match());
        assertEquals(List.of(), requirement.features());
    }

    @Test
    void allFeaturesNormalizesAndDeduplicatesKeys() {
        LicenseRequirement requirement = LicenseRequirement.allFeatures(
                " Report.Export ",
                "report.schedule",
                "REPORT.EXPORT"
        );

        assertEquals(LicenseRequirement.Match.ALL, requirement.match());
        assertEquals(List.of("report.export", "report.schedule"), requirement.features());
    }

    @Test
    void anyFeaturesUsesExplicitAnyMatch() {
        LicenseRequirement requirement = LicenseRequirement.anyFeatures("basic", "pro");

        assertEquals(LicenseRequirement.Match.ANY, requirement.match());
        assertEquals("any", requirement.wireMatch());
    }

    @Test
    void featureRequirementRejectsEmptyFeatures() {
        assertThrows(IllegalArgumentException.class, LicenseRequirement::allFeatures);
        assertThrows(IllegalArgumentException.class, () -> LicenseRequirement.anyFeatures(" "));
    }
}
