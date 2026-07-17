package com.jw.ignition.license.service;

import com.jw.ignition.license.model.MachineInfoModel;
import com.jw.ignition.license.model.MachineInfoRequestModel;
import com.jw.ignition.license.model.MachineInfoSnapshotModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class MachineInfoService {

    // 7 machine information that able to retrieve
    public static final String MACHINE_GUID = "machineGuid";
    public static final String SYSTEM_UUID = "systemUuid";
    public static final String BIOS_SERIAL_NUMBER = "biosSerialNumber";
    public static final String MOTHERBOARD_SERIAL_NUMBER = "motherboardSerialNumber";
    public static final String DISK_SERIAL_NUMBER = "diskSerialNumber";
    public static final String MAC_ADDRESS = "macAddress";
    public static final String CPU_PROCESSOR_ID = "cpuProcessorId";

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> INVALID_VALUES = Set.of(
        "0",
        "NONE",
        "NULL",
        "UNKNOWN",
        "NOT SPECIFIED",
        "NOT AVAILABLE",
        "NOT APPLICABLE",
        "SYSTEM SERIAL NUMBER",
        "TO BE FILLED BY O.E.M.",
        "TO BE FILLED BY OEM",
        "DEFAULT STRING",
        "00000000-0000-0000-0000-000000000000",
        "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"
    );

    // Collect all the machine information
    public MachineInfoSnapshotModel collect() {
        List<String> warnings = new ArrayList<>();
        MachineInfoSnapshotModel snapshot = new MachineInfoSnapshotModel();

        addIdentifier(snapshot, warnings, MACHINE_GUID, collectMachineGuid());
        addIdentifier(snapshot, warnings, SYSTEM_UUID, collectSystemUuid());
        addIdentifier(snapshot, warnings, BIOS_SERIAL_NUMBER, collectBiosSerialNumber());
        addIdentifier(snapshot, warnings, MOTHERBOARD_SERIAL_NUMBER, collectBaseboardSerialNumber());
        addIdentifier(snapshot, warnings, DISK_SERIAL_NUMBER, collectDiskSerialNumber());
        addIdentifier(snapshot, warnings, CPU_PROCESSOR_ID, collectCpuProcessorId());
        addMacAddresses(snapshot, warnings, collectMacAddresses());

        snapshot.warnings.addAll(warnings);
        snapshot.capturedIdentifiers = countIdentifiers(snapshot.hashedMachineInfo);
        return snapshot;
    }

    public MachineInfoRequestModel createRequest(MachineInfoSnapshotModel snapshot) {
        MachineInfoRequestModel request = new MachineInfoRequestModel();
        request.capturedIdentifiers = snapshot == null ? 0 : snapshot.capturedIdentifiers;
        if (snapshot != null) {
            request.machineInfo = snapshot.rawMachineInfo;
            request.hashedMachineInfo = snapshot.hashedMachineInfo;
            request.rawIdentifiers.putAll(snapshot.rawIdentifiers);
            request.hashedIdentifiers.putAll(snapshot.hashedIdentifiers);
            request.warnings.addAll(snapshot.warnings);
        }
        return request;
    }

    // form a single string txt file to upload at license generator web application
    public String generateRequestFile(MachineInfoRequestModel request) {
        MachineInfoModel machineInfo = request == null ? null : request.machineInfo;
        List<String> identifiers = new ArrayList<>();

        addTextIdentifier(identifiers, SYSTEM_UUID, machineInfo == null ? "" : machineInfo.systemUuid);
        addTextIdentifier(identifiers, BIOS_SERIAL_NUMBER, machineInfo == null ? "" : machineInfo.biosSerialNumber);
        addTextIdentifier(identifiers, MOTHERBOARD_SERIAL_NUMBER, machineInfo == null ? "" : machineInfo.motherboardSerialNumber);
        addTextIdentifier(identifiers, DISK_SERIAL_NUMBER, machineInfo == null ? "" : machineInfo.diskSerialNumber);
        addTextIdentifier(identifiers, CPU_PROCESSOR_ID, machineInfo == null ? "" : machineInfo.cpuProcessorId);
        addTextIdentifier(identifiers, MACHINE_GUID, machineInfo == null ? "" : machineInfo.machineGuid);
        if (machineInfo != null && machineInfo.macAddress != null) {
            addTextIdentifier(identifiers, MAC_ADDRESS, String.join(", ", machineInfo.macAddress));
        }

        return String.join(":::", identifiers);
    }

    public String normalizeIdentifier(String key, String value) {
        if (MAC_ADDRESS.equals(key)) {
            return normalizeMacAddress(value);
        }
        String normalized = normalizeText(value);
        return isInvalidIdentifierValue(normalized) ? "" : normalized;
    }

    public String hashNormalized(String normalizedValue) {
        String value = trim(normalizedValue);
        if (value.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private void addIdentifier(MachineInfoSnapshotModel snapshot, List<String> warnings, String key, String rawValue) {
        String normalized = normalizeIdentifier(key, rawValue);
        if (normalized.isBlank()) {
            warnings.add("Identifier unavailable: " + key);
            return;
        }

        snapshot.rawIdentifiers.put(key, trim(rawValue));
        snapshot.normalizedIdentifiers.put(key, normalized);
        snapshot.hashedIdentifiers.put(key, hashNormalized(normalized));
        setStringField(snapshot.rawMachineInfo, key, trim(rawValue));
        setStringField(snapshot.normalizedMachineInfo, key, normalized);
        setStringField(snapshot.hashedMachineInfo, key, hashNormalized(normalized));
    }

    private void addMacAddresses(MachineInfoSnapshotModel snapshot, List<String> warnings, List<String> rawValues) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (rawValues != null) {
            for (String rawValue : rawValues) {
                String normalized = normalizeIdentifier(MAC_ADDRESS, rawValue);
                if (!normalized.isBlank()) {
                    unique.putIfAbsent(normalized, trim(rawValue));
                }
            }
        }

        if (unique.isEmpty()) {
            warnings.add("Identifier unavailable: " + MAC_ADDRESS);
            return;
        }

        for (Map.Entry<String, String> entry : unique.entrySet()) {
            String normalized = entry.getKey();
            String rawValue = entry.getValue();
            String hash = hashNormalized(normalized);

            snapshot.rawMachineInfo.macAddress.add(rawValue);
            snapshot.normalizedMachineInfo.macAddress.add(normalized);
            snapshot.hashedMachineInfo.macAddress.add(hash);
        }

        snapshot.rawIdentifiers.put(MAC_ADDRESS, String.join(",", snapshot.rawMachineInfo.macAddress));
        snapshot.normalizedIdentifiers.put(MAC_ADDRESS, String.join(",", snapshot.normalizedMachineInfo.macAddress));
        snapshot.hashedIdentifiers.put(MAC_ADDRESS, String.join(",", snapshot.hashedMachineInfo.macAddress));
    }

    private void setStringField(MachineInfoModel model, String key, String value) {
        if (MACHINE_GUID.equals(key)) {
            model.machineGuid = value;
        } else if (SYSTEM_UUID.equals(key)) {
            model.systemUuid = value;
        } else if (BIOS_SERIAL_NUMBER.equals(key)) {
            model.biosSerialNumber = value;
        } else if (MOTHERBOARD_SERIAL_NUMBER.equals(key)) {
            model.motherboardSerialNumber = value;
        } else if (DISK_SERIAL_NUMBER.equals(key)) {
            model.diskSerialNumber = value;
        } else if (CPU_PROCESSOR_ID.equals(key)) {
            model.cpuProcessorId = value;
        }
    }

    private int countIdentifiers(MachineInfoModel model) {
        if (model == null) {
            return 0;
        }
        int count = 0;
        count += trim(model.machineGuid).isBlank() ? 0 : 1;
        count += trim(model.systemUuid).isBlank() ? 0 : 1;
        count += trim(model.biosSerialNumber).isBlank() ? 0 : 1;
        count += trim(model.motherboardSerialNumber).isBlank() ? 0 : 1;
        count += trim(model.diskSerialNumber).isBlank() ? 0 : 1;
        count += trim(model.cpuProcessorId).isBlank() ? 0 : 1;
        count += model.macAddress == null || model.macAddress.isEmpty() ? 0 : 1;
        return count;
    }

    private void addTextIdentifier(List<String> identifiers, String key, String value) {
        String cleanValue = sanitizeTextIdentifierValue(value);
        if (!cleanValue.isBlank()) {
            identifiers.add(key + ":" + cleanValue);
        }
    }

    private String sanitizeTextIdentifierValue(String value) {
        return trim(value)
            .replace('\u0000', ' ')
            .replaceAll("\\R+", " ")
            .replace(":::", ":")
            .trim();
    }

    private String collectMachineGuid() {
        if (isWindows()) {
            return runWindowsPowerShell(
                    "(Get-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -Name MachineGuid).MachineGuid"
            );
        }
        return "";
    }

    private String collectSystemUuid() {
        if (isWindows()) {
            return runWindowsPowerShell("(Get-CimInstance -ClassName Win32_ComputerSystemProduct | Select-Object -First 1 -ExpandProperty UUID)");
        }
        if (isMac()) {
            return parseQuotedIoregValue(runCommand("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), "IOPlatformUUID");
        }
        return firstNonBlank(
            readFirstReadable(Path.of("/sys/class/dmi/id/product_uuid")),
            runCommand("dmidecode", "-s", "system-uuid")
        );
    }

    private String collectBiosSerialNumber() {
        if (isWindows()) {
            return runWindowsPowerShell("(Get-CimInstance -ClassName Win32_BIOS | Select-Object -First 1 -ExpandProperty SerialNumber)");
        }
        if (isMac()) {
            return parseQuotedIoregValue(runCommand("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), "IOPlatformSerialNumber");
        }
        return firstNonBlank(
            readFirstReadable(Path.of("/sys/class/dmi/id/product_serial")),
            runCommand("dmidecode", "-s", "system-serial-number")
        );
    }

    private String collectBaseboardSerialNumber() {
        if (isWindows()) {
            return runWindowsPowerShell("(Get-CimInstance -ClassName Win32_BaseBoard | Select-Object -First 1 -ExpandProperty SerialNumber)");
        }
        if (isMac()) {
            return parseQuotedIoregValue(runCommand("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), "IOPlatformSerialNumber");
        }
        return firstNonBlank(
            readFirstReadable(Path.of("/sys/class/dmi/id/board_serial")),
            runCommand("dmidecode", "-s", "baseboard-serial-number")
        );
    }

    private String collectDiskSerialNumber() {
        if (isWindows()) {
            return runWindowsPowerShell(
                "Get-CimInstance -ClassName Win32_DiskDrive | "
                    + "Sort-Object Index | "
                    + "Where-Object { $_.SerialNumber } | "
                    + "Select-Object -First 1 -ExpandProperty SerialNumber"
            );
        }
        if (isMac()) {
            return parseAfterColon(runCommand("system_profiler", "SPSerialATADataType", "SPNVMeDataType"), "Serial Number");
        }
        return firstNonBlank(
            collectLinuxDiskSerialNumber(),
            firstLine(runCommand("lsblk", "-ndo", "SERIAL"))
        );
    }

    private String collectCpuProcessorId() {
        if (isWindows()) {
            return runWindowsPowerShell(
                    "(Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1 -ExpandProperty ProcessorId)"
            );
        }

        return "";
    }

    private List<String> collectMacAddresses() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            interfaces.sort(Comparator.comparing(NetworkInterface::getName, String.CASE_INSENSITIVE_ORDER));

            List<MacCandidate> preferred = new ArrayList<>();
            List<MacCandidate> fallback = new ArrayList<>();
            for (NetworkInterface networkInterface : interfaces) {
                byte[] hardwareAddress = networkInterface.getHardwareAddress();
                if (hardwareAddress == null || hardwareAddress.length == 0) {
                    continue;
                }
                if (networkInterface.isLoopback()) {
                    continue;
                }

                String mac = toMacAddress(hardwareAddress);
                if (normalizeMacAddress(mac).isBlank()) {
                    continue;
                }

                MacCandidate candidate = new MacCandidate(networkInterface.getName(), networkInterface.getDisplayName(), mac);
                if (networkInterface.isUp() && !networkInterface.isVirtual() && !looksVirtualNetworkInterface(candidate)) {
                    preferred.add(candidate);
                } else {
                    fallback.add(candidate);
                }
            }

            List<MacCandidate> selected = preferred.isEmpty() ? fallback : preferred;
            List<String> values = new ArrayList<>();
            for (MacCandidate candidate : selected) {
                values.add(candidate.macAddress());
            }
            return values;
        } catch (SocketException e) {
            return List.of();
        }
    }

    private String collectLinuxDiskSerialNumber() {
        Path blockRoot = Path.of("/sys/block");
        if (!Files.isDirectory(blockRoot)) {
            return "";
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(blockRoot)) {
            List<Path> devices = new ArrayList<>();
            for (Path device : stream) {
                devices.add(device);
            }
            devices.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            for (Path device : devices) {
                String name = device.getFileName().toString();
                if (isLinuxVirtualBlockDevice(name)) {
                    continue;
                }
                String serial = readFirstReadable(device.resolve("device").resolve("serial"));
                if (!normalizeIdentifier(DISK_SERIAL_NUMBER, serial).isBlank()) {
                    return serial;
                }
            }
        } catch (IOException | SecurityException ignored) {
            return "";
        }
        return "";
    }

    private boolean isLinuxVirtualBlockDevice(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.startsWith("loop")
            || normalized.startsWith("ram")
            || normalized.startsWith("dm-")
            || normalized.startsWith("md")
            || normalized.startsWith("zram")
            || normalized.startsWith("sr")
            || normalized.startsWith("fd");
    }

    private boolean looksVirtualNetworkInterface(MacCandidate candidate) {
        String value = (candidate.name() + " " + candidate.displayName()).toLowerCase(Locale.ROOT);
        return value.contains("virtual")
            || value.contains("vmware")
            || value.contains("vbox")
            || value.contains("hyper-v")
            || value.contains("docker")
            || value.contains("wsl")
            || value.contains("vpn")
            || value.contains("tap")
            || value.contains("tunnel")
            || value.contains("loopback")
            || value.contains("bluetooth");
    }

    private String normalizeMacAddress(String value) {
        String raw = trim(value);
        if (raw.isBlank()) {
            return "";
        }
        String compact = raw.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (compact.length() != 12 || compact.matches("0+")) {
            return "";
        }
        return compact;
    }

    private String normalizeText(String value) {
        String normalized = trim(value)
            .replace('\u0000', ' ')
            .replaceAll("\\s+", " ")
            .toUpperCase(Locale.ROOT);
        return normalized;
    }

    private boolean isInvalidIdentifierValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (INVALID_VALUES.contains(value)) {
            return true;
        }
        return value.matches("0+") || value.matches("F+");
    }

    private String readFirstReadable(Path path) {
        try {
            if (Files.isRegularFile(path) && Files.isReadable(path)) {
                return firstLine(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (IOException | SecurityException ignored) {
            return "";
        }
        return "";
    }

    private String runWindowsPowerShell(String script) {
        String value = runCommand("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
        if (!value.isBlank()) {
            return firstLine(value);
        }
        return firstLine(runCommand("pwsh", "-NoProfile", "-Command", script));
    }

    private String runCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread outputReader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(output);
                } catch (IOException ignored) {
                    // Best effort only. Missing identifiers are reported as warnings in the snapshot.
                }
            }, "license-machine-info-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            outputReader.join(1000);
            if (process.exitValue() != 0) {
                return "";
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException | SecurityException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private String parseQuotedIoregValue(String output, String key) {
        String prefix = "\"" + key + "\" = \"";
        for (String line : output.split("\\R")) {
            int start = line.indexOf(prefix);
            if (start < 0) {
                continue;
            }
            String value = line.substring(start + prefix.length());
            int end = value.indexOf('"');
            return end >= 0 ? value.substring(0, end) : value;
        }
        return "";
    }

    private String parseAfterColon(String output, String key) {
        String prefix = key + ":";
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return "";
    }

    private String firstLine(String value) {
        for (String line : trim(value).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return "";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private String toMacAddress(byte[] hardwareAddress) {
        StringBuilder value = new StringBuilder();
        for (byte part : hardwareAddress) {
            if (!value.isEmpty()) {
                value.append(':');
            }
            value.append(String.format("%02X", part));
        }
        return value.toString();
    }

    private boolean isWindows() {
        return osName().contains("win");
    }

    private boolean isMac() {
        return osName().contains("mac");
    }

    private String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private record MacCandidate(String name, String displayName, String macAddress) {
    }
}
