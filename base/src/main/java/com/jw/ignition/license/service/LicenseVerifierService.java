package com.jw.ignition.license.service;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.gson.JsonSyntaxException;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.jw.ignition.license.crypto.RsaPublicKeys.getPublicKeyVersion;

public final class LicenseVerifierService {
    private static final String SUPPORTED_ALGORITHM = "SHA256withRSA";

    private final LicenseFileStoreService fileStore;
    private final MachineInfoService machineInfoService;
    private final Gson gson = new GsonBuilder().create();

    public LicenseVerifierService(LicenseFileStoreService fileStore, MachineInfoService machineInfoService) {
        if (fileStore == null) {
            throw new IllegalArgumentException("License file store is required.");
        }
        if (machineInfoService == null) {
            throw new IllegalArgumentException("Machine info service is required.");
        }
        this.fileStore = fileStore;
        this.machineInfoService = machineInfoService;
    }

    public Path licensePath() {
        return fileStore.licensePath();
    }

    // verify license based on file exist in Ignition data installation module directory
    public LicenseStatusModel verify(LicenseVerifierConfigModel config) {
        validateConfig(config);

        LicenseStatusModel status = baseStatus(config.clock);
        status.licensePath = fileStore.licensePath().toString();

        if (!fileStore.exists()) {
            return fail(status, LicenseStatusCode.MISSING_FILE, "License file does not exist.");
        }

        LicenseFileModel licenseFile;
        try {
            licenseFile = parseLicenseFile(fileStore.read());
        } catch (IllegalStateException e) {
            status.errors.add(rootMessage(e));
            return fail(status, LicenseStatusCode.IO_ERROR, "Unable to read license file.");
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            status.errors.add(rootMessage(e));
            return fail(status, LicenseStatusCode.INVALID_JSON, "License file is not valid JSON.");
        }

        return verifyLicenseFile(status, licenseFile, config);
    }

    // verify license when the license file is uploaded initially
    public LicenseStatusModel verifyContent(String content, LicenseVerifierConfigModel config) {
        validateConfig(config);

        LicenseStatusModel status = baseStatus(config.clock);
        status.licensePath = fileStore.licensePath().toString();

        LicenseFileModel licenseFile;
        try {
            licenseFile = parseLicenseFile(content);
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            status.errors.add(rootMessage(e));
            return fail(status, LicenseStatusCode.INVALID_JSON, "License file is not valid JSON.");
        }

        return verifyLicenseFile(status, licenseFile, config);
    }

    private LicenseStatusModel verifyLicenseFile(LicenseStatusModel status, LicenseFileModel licenseFile, LicenseVerifierConfigModel config) {
        copyOuterFields(status, licenseFile);
        LicenseStatusModel structureStatus = validateOuterStructure(status, licenseFile, config);
        if (structureStatus != null) {
            return structureStatus;
        }

        if (!verifySignature(licenseFile, config, status)) {
            return fail(status, LicenseStatusCode.INVALID_SIGNATURE, "License signature is invalid.");
        }

        LicensePayloadModel payload;
        try {
            payload = decodePayload(licenseFile.payloadBase64);
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            status.errors.add(rootMessage(e));
            return fail(status, LicenseStatusCode.INVALID_PAYLOAD, "License payload is invalid.");
        }

        copyPayloadFields(status, payload);
        LicenseStatusModel payloadStatus = validatePayload(status, payload, config);
        if (payloadStatus != null) {
            return payloadStatus;
        }

        MachineInfoSnapshotModel currentMachine = machineInfoService.collect();
        status.currentIdentifierCount = countMachineInfoIdentifiers(currentMachine.hashedMachineInfo);
        status.warnings.addAll(currentMachine.warnings);

        if (status.currentIdentifierCount == 0) {
            return fail(status, LicenseStatusCode.INSUFFICIENT_MACHINE_INFO, "No machine identifiers could be collected.");
        }

        return compareMachineInfo(status, payload.machineInfo, currentMachine.rawMachineInfo, config);
    }

    private LicenseFileModel parseLicenseFile(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("License file is empty.");
        }
        LicenseFileModel licenseFile = gson.fromJson(content, LicenseFileModel.class);
        if (licenseFile == null) {
            throw new IllegalArgumentException("License file is empty.");
        }
        return licenseFile;
    }

    private LicensePayloadModel decodePayload(String payloadBase64) {
        byte[] payloadBytes = Base64.getDecoder().decode(payloadBase64);
        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
        LicensePayloadModel payload = gson.fromJson(payloadJson, LicensePayloadModel.class);
        if (payload == null) {
            throw new IllegalArgumentException("Decoded license payload is empty.");
        }
        return payload;
    }

    private LicenseStatusModel validateOuterStructure(
        LicenseStatusModel status,
        LicenseFileModel licenseFile,
        LicenseVerifierConfigModel config
    ) {
        if (licenseFile.schemaVersion != config.schemaVersion) {
            return fail(status, LicenseStatusCode.UNSUPPORTED_SCHEMA_VERSION, "License schema version is not supported.");
        }
        if (isBlank(licenseFile.keyId)) {
            status.errors.add("keyId is required.");
        }

        String currentKeyVersion = licenseFile.keyId.replaceFirst("^key-" + String.valueOf(Year.now().getValue()) + "-", "");
        if (!currentKeyVersion.equals(getPublicKeyVersion()))
        {
            return fail(status, LicenseStatusCode.LICENSE_VERSION_NOT_MATCH, "License signature key version not tally.");
        }

        if (!SUPPORTED_ALGORITHM.equalsIgnoreCase(trim(licenseFile.algorithm))) {
            return fail(status, LicenseStatusCode.UNSUPPORTED_ALGORITHM, "License signature algorithm is not supported.");
        }
        if (isBlank(licenseFile.payloadBase64)) {
            status.errors.add("payloadBase64 is required.");
        }
        if (isBlank(licenseFile.signature)) {
            status.errors.add("signature is required.");
        }
        if (!status.errors.isEmpty()) {
            return fail(status, LicenseStatusCode.INVALID_FILE, "License file is missing required fields.");
        }
        return null;
    }

    private LicenseStatusModel validatePayload(
        LicenseStatusModel status,
        LicensePayloadModel payload,
        LicenseVerifierConfigModel config
    ) {
        if (isBlank(payload.licenseId)) {
            status.errors.add("licenseId is required.");
        }
        if (payload.module == null || isBlank(payload.module.moduleId)) {
            status.errors.add("module.moduleId is required.");
        }
        if (payload.machineInfo == null) {
            status.errors.add("machineInfo is required.");
        }
        if (status.features.isEmpty()) {
            status.errors.add("no available features.");
        }
        if (!status.errors.isEmpty()) {
            return fail(status, LicenseStatusCode.INVALID_PAYLOAD, "License payload is missing required fields.");
        }

        if (!trim(config.moduleId).equals(trim(payload.module.moduleId))) {
            return fail(status, LicenseStatusCode.WRONG_MODULE, "License is for a different module.");
        }

        if (!isBlank(payload.expiresAt)) {
            try {
                Instant expiresAt = Instant.parse(payload.expiresAt.trim());
                Instant now = Instant.now(config.clock);
                if (!expiresAt.isAfter(now)) {
                    return fail(status, LicenseStatusCode.EXPIRED, "License has expired.");
                }
            } catch (DateTimeParseException e) {
                status.errors.add("expiresAt must be an ISO-8601 UTC timestamp.");
                return fail(status, LicenseStatusCode.INVALID_PAYLOAD, "License expiry timestamp is invalid.");
            }
        }

        if (payload.capturedIdentifiers != countMachineInfoIdentifiers(payload.machineInfo)) {
            status.warnings.add("capturedIdentifiers does not match machineInfo size.");
        }
        return null;
    }

    // verify the file signature with the embedded public key
    private boolean verifySignature(LicenseFileModel licenseFile, LicenseVerifierConfigModel config, LicenseStatusModel status) {
        try {
            byte[] payloadBytes = decodeBase64Field("payloadBase64", licenseFile.payloadBase64);
            byte[] signatureBytes = decodeBase64Field("signature", licenseFile.signature);
            int expectedSignatureLength = expectedSignatureLength(config.publicKey);

            if (expectedSignatureLength > 0 && signatureBytes.length != expectedSignatureLength) {
                status.errors.add("Signature byte length " + signatureBytes.length
                    + " does not match RSA key byte length " + expectedSignatureLength + ".");
                status.errors.add(signatureDiagnostic(payloadBytes, signatureBytes, config.publicKey));
                return false;
            }

            Signature verifier = Signature.getInstance(SUPPORTED_ALGORITHM);
            verifier.initVerify(config.publicKey);
            verifier.update(payloadBytes);

            boolean result = verifier.verify(signatureBytes);
            if (!result) {
                status.errors.add("Signature bytes did not match payload bytes for the configured public key.");
                status.errors.add(signatureDiagnostic(payloadBytes, signatureBytes, config.publicKey));
            }

            return result;
        } catch (IllegalArgumentException e) {
            status.errors.add(e.getMessage());
            return false;
        } catch (GeneralSecurityException e) {
            status.errors.add(rootMessage(e));
            return false;
        }
    }

    // compare machine identifiers between generated license file and current device
    private LicenseStatusModel compareMachineInfo(
        LicenseStatusModel status,
        MachineInfoModel licensedMachineInfo,
        MachineInfoModel currentMachineInfo,
        LicenseVerifierConfigModel config
    ) {
        Map<String, List<String>> normalizedLicensedMachineInfo = normalizedHashModel(licensedMachineInfo);
        Map<String, List<String>> normalizedCurrentMachineInfo = normalizedHashModel(currentMachineInfo);
        status.licenseIdentifierCount = normalizedLicensedMachineInfo.size();

        if (status.licenseIdentifierCount < config.minimumLicenseIdentifiers) {
            return fail(status, LicenseStatusCode.INVALID_PAYLOAD, "License does not contain enough machine identifiers.");
        }

        int matched = 0;
        for (Map.Entry<String, List<String>> entry : normalizedLicensedMachineInfo.entrySet()) {
            String key = entry.getKey();
            List<String> licenseInfo = entry.getValue();
            List<String> currentInfo = normalizedCurrentMachineInfo.getOrDefault(key, List.of());
            String matchedInfo = firstMatchingInfo(licenseInfo, currentInfo);

            IdentifierMatchModel detail = new IdentifierMatchModel();
            detail.key = key;
            detail.licenseInfo = joinInfo(licenseInfo);
            detail.currentInfo = isBlank(matchedInfo) ? joinInfo(currentInfo) : matchedInfo;
            detail.currentAvailable = !currentInfo.isEmpty();
            detail.matched = !isBlank(matchedInfo);
            status.identifierMatches.put(key, detail);

            if (detail.matched) {
                matched++;
            }
        }

        status.matchedIdentifiers = matched;
        status.requiredMatchedIdentifiers = requiredMatches(status.licenseIdentifierCount, config.minimumMatchRatio);
        status.matchRatio = status.licenseIdentifierCount == 0
            ? 0.0d
            : matched / (double) status.licenseIdentifierCount;

        if (matched < status.requiredMatchedIdentifiers) {
            return fail(status, LicenseStatusCode.MACHINE_MISMATCH, "License does not match this machine.");
        }

        status.licensed = true;
        status.code = LicenseStatusCode.LICENSED;
        status.message = "License is valid.";
        return status;
    }

    private Map<String, List<String>> normalizedHashModel(MachineInfoModel hashes) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        if (hashes == null) {
            return normalized;
        }
        addHash(normalized, MachineInfoService.MACHINE_GUID, hashes.machineGuid);
        addHash(normalized, MachineInfoService.SYSTEM_UUID, hashes.systemUuid);
        addHash(normalized, MachineInfoService.BIOS_SERIAL_NUMBER, hashes.biosSerialNumber);
        addHash(normalized, MachineInfoService.MOTHERBOARD_SERIAL_NUMBER, hashes.motherboardSerialNumber);
        addHash(normalized, MachineInfoService.DISK_SERIAL_NUMBER, hashes.diskSerialNumber);
        addHash(normalized, MachineInfoService.CPU_PROCESSOR_ID, hashes.cpuProcessorId);
        addHashes(normalized, MachineInfoService.MAC_ADDRESS, hashes.macAddress);
        return normalized;
    }

    private void addHash(Map<String, List<String>> hashes, String key, String value) {
        String hash = normalizeHash(value);
        if (!hash.isBlank()) {
            hashes.put(key, List.of(hash));
        }
    }

    private void addHashes(Map<String, List<String>> hashes, String key, List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String hash = normalizeHash(value);
                if (!hash.isBlank() && !normalized.contains(hash)) {
                    normalized.add(hash);
                }
            }
        }
        if (!normalized.isEmpty()) {
            hashes.put(key, normalized);
        }
    }

    private int countMachineInfoIdentifiers(MachineInfoModel machineInfo) {
        return normalizedHashModel(machineInfo).size();
    }

    private String firstMatchingInfo(List<String> licensedInfos, List<String> currentInfo) {
        for (String licensedInfo : licensedInfos) {
            if (currentInfo.contains(licensedInfo)) {
                return licensedInfo;
            }
        }
        return "";
    }

    private String joinInfo(List<String> info) {
        return info == null || info.isEmpty() ? "" : String.join(", ", info);
    }

    // mathematical function to calculate minimum number of identifier required
    private int requiredMatches(int identifierCount, double minimumMatchRatio) {
        double ratio = Math.max(0.0d, Math.min(1.0d, minimumMatchRatio));
        return Math.max(1, (int) Math.floor(identifierCount * ratio) + 1);
    }

    private LicenseStatusModel baseStatus(Clock clock) {
        LicenseStatusModel status = LicenseStatusModel.empty();
        status.verifiedAt = Instant.now(clock).toString();
        return status;
    }

    private LicenseStatusModel fail(LicenseStatusModel status, LicenseStatusCode code, String message) {
        status.licensed = false;
        status.code = code;
        status.message = message;
        return status;
    }

    private void copyOuterFields(LicenseStatusModel status, LicenseFileModel licenseFile) {
        status.schemaVersion = licenseFile.schemaVersion;
        status.keyId = trim(licenseFile.keyId);
        status.algorithm = trim(licenseFile.algorithm);
    }

    private void copyPayloadFields(LicenseStatusModel status, LicensePayloadModel payload) {
        status.licenseId = trim(payload.licenseId);
        status.client = trim(payload.client);
        status.moduleId = payload.module == null ? "" : trim(payload.module.moduleId);
        status.issuedAt = trim(payload.issuedAt);
        status.expiresAt = trim(payload.expiresAt);
        status.capturedIdentifiers = payload.capturedIdentifiers;
        status.features = normalizeFeatures(payload.features);
    }

    private List<String> normalizeFeatures(List<String> features) {
        List<String> normalized = new ArrayList<>();
        if (features == null) {
            return normalized;
        }
        for (String feature : features) {
            String value = trim(feature).toLowerCase(Locale.ROOT);
            if (!value.isBlank() && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private void validateConfig(LicenseVerifierConfigModel config) {
        if (config == null) {
            throw new IllegalArgumentException("License verifier config is required.");
        }
        if (isBlank(config.moduleId)) {
            throw new IllegalArgumentException("Module id is required.");
        }
        if (config.publicKey == null) {
            throw new IllegalArgumentException("RSA public key is required.");
        }
        if (config.clock == null) {
            config.clock = Clock.systemUTC();
        }
        if (config.minimumLicenseIdentifiers < 1) {
            config.minimumLicenseIdentifiers = 1;
        }
    }

    private String normalizeHash(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private byte[] decodeBase64Field(String name, String value) {
        try {
            return Base64.getDecoder().decode(trim(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not valid Base64: " + rootMessage(e), e);
        }
    }

    private int expectedSignatureLength(PublicKey publicKey) {
        if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            return (rsaPublicKey.getModulus().bitLength() + 7) / 8;
        }
        return -1;
    }

    private String signatureDiagnostic(byte[] payloadBytes, byte[] signatureBytes, PublicKey publicKey) {
        StringBuilder diagnostic = new StringBuilder("Signature diagnostics: ");
        diagnostic.append("algorithm=").append(SUPPORTED_ALGORITHM);
        diagnostic.append(", payloadBytes=").append(payloadBytes.length);
        diagnostic.append(", payloadSha256=").append(sha256Hex(payloadBytes));
        diagnostic.append(", signatureBytes=").append(signatureBytes.length);
        diagnostic.append(", publicKeyAlgorithm=").append(publicKey.getAlgorithm());
        diagnostic.append(", publicKeyFormat=").append(publicKey.getFormat());
        diagnostic.append(", publicKeySha256=").append(publicKeySha256(publicKey));

        if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            diagnostic.append(", rsaKeyBits=").append(rsaPublicKey.getModulus().bitLength());
            diagnostic.append(", expectedSignatureBytes=").append(expectedSignatureLength(publicKey));
        }

        return diagnostic.toString();
    }

    private String publicKeySha256(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        return encoded == null ? "unavailable" : sha256Hex(encoded);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
