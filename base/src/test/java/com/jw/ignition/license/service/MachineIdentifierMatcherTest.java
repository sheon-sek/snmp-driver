package com.jw.ignition.license.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.jw.ignition.license.model.MachineInfoModel;
import org.junit.jupiter.api.Test;

class MachineIdentifierMatcherTest {

    @Test
    void hashesByKeyLowerCasesDropsBlanksAndDeduplicatesMacs() {
        MachineInfoModel model = new MachineInfoModel();
        model.systemUuid = " SHA256:AA ";
        model.machineGuid = "  ";
        model.macAddress.add("sha256:m1");
        model.macAddress.add("SHA256:M1");
        model.macAddress.add("sha256:m2");

        Map<String, List<String>> hashes = MachineIdentifierMatcher.hashesByKey(model);

        assertEquals(Map.of(MachineInfoService.SYSTEM_UUID, List.of("sha256:aa"),
                MachineInfoService.MAC_ADDRESS, List.of("sha256:m1", "sha256:m2")), hashes);
    }

    @Test
    void requiredMatchesIsMoreThanHalfAtTheLicenseRatio() {
        assertEquals(1, MachineIdentifierMatcher.requiredMatches(1, 0.5d));
        assertEquals(2, MachineIdentifierMatcher.requiredMatches(2, 0.5d));
        assertEquals(2, MachineIdentifierMatcher.requiredMatches(3, 0.5d));
        assertEquals(3, MachineIdentifierMatcher.requiredMatches(4, 0.5d));
        assertEquals(1, MachineIdentifierMatcher.requiredMatches(0, 0.5d));
    }

    @Test
    void matchesCountsAKeyWhenAnyOfItsHashesIsPresent() {
        Map<String, List<String>> reference = Map.of(
                "a", List.of("1"), "b", List.of("2"), "mac", List.of("m1", "m2"));

        assertEquals(2, MachineIdentifierMatcher.countMatches(reference,
                Map.of("a", List.of("1"), "mac", List.of("m2", "m9"))));
        assertTrue(MachineIdentifierMatcher.matches(reference,
                Map.of("a", List.of("1"), "mac", List.of("m2")), 0.5d));
        assertFalse(MachineIdentifierMatcher.matches(reference,
                Map.of("a", List.of("1"), "b", List.of("x")), 0.5d));
    }
}
