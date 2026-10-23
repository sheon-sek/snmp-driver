package com.jw.ignition.license.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jw.ignition.license.model.MachineInfoModel;

/** The License's proportional machine match, shared by every caller that compares machine identifier hashes. */
public final class MachineIdentifierMatcher {

    private MachineIdentifierMatcher() {
    }

    /** Identifier key to its non-blank, lower-cased hashes; keys without a hash are left out. */
    public static Map<String, List<String>> hashesByKey(MachineInfoModel hashes) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        if (hashes == null) {
            return normalized;
        }
        addHashes(normalized, MachineInfoService.MACHINE_GUID, List.of(nullToEmpty(hashes.machineGuid)));
        addHashes(normalized, MachineInfoService.SYSTEM_UUID, List.of(nullToEmpty(hashes.systemUuid)));
        addHashes(normalized, MachineInfoService.BIOS_SERIAL_NUMBER, List.of(nullToEmpty(hashes.biosSerialNumber)));
        addHashes(normalized, MachineInfoService.MOTHERBOARD_SERIAL_NUMBER,
                List.of(nullToEmpty(hashes.motherboardSerialNumber)));
        addHashes(normalized, MachineInfoService.DISK_SERIAL_NUMBER, List.of(nullToEmpty(hashes.diskSerialNumber)));
        addHashes(normalized, MachineInfoService.CPU_PROCESSOR_ID, List.of(nullToEmpty(hashes.cpuProcessorId)));
        addHashes(normalized, MachineInfoService.MAC_ADDRESS, hashes.macAddress == null ? List.of() : hashes.macAddress);
        return normalized;
    }

    /** A reference key matches when any of its hashes is among the current hashes for that key. */
    public static String firstMatch(List<String> referenceHashes, List<String> currentHashes) {
        for (String hash : referenceHashes) {
            if (currentHashes.contains(hash)) {
                return hash;
            }
        }
        return "";
    }

    public static int countMatches(Map<String, List<String>> reference, Map<String, List<String>> current) {
        int matched = 0;
        for (Map.Entry<String, List<String>> entry : reference.entrySet()) {
            if (!firstMatch(entry.getValue(), current.getOrDefault(entry.getKey(), List.of())).isEmpty()) {
                matched++;
            }
        }
        return matched;
    }

    /** Minimum matched keys: strictly more than {@code ratio} of the reference keys, and at least one. */
    public static int requiredMatches(int identifierCount, double minimumMatchRatio) {
        double ratio = Math.max(0.0d, Math.min(1.0d, minimumMatchRatio));
        return Math.max(1, (int) Math.floor(identifierCount * ratio) + 1);
    }

    public static boolean matches(Map<String, List<String>> reference, Map<String, List<String>> current,
                                  double minimumMatchRatio) {
        return countMatches(reference, current) >= requiredMatches(reference.size(), minimumMatchRatio);
    }

    private static void addHashes(Map<String, List<String>> hashes, String key, List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String hash = nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
            if (!hash.isBlank() && !normalized.contains(hash)) {
                normalized.add(hash);
            }
        }
        if (!normalized.isEmpty()) {
            hashes.put(key, normalized);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
