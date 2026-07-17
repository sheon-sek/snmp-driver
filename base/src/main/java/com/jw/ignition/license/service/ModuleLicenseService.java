package com.jw.ignition.license.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.license.audit.LicenseAuditService;
import com.jw.ignition.license.crypto.RsaPublicKeys;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.exception.LicenseAccessException;
import com.jw.ignition.license.exception.LicenseFeatureAccessException;
import com.jw.ignition.license.exception.LicenseUploadException;
import com.jw.ignition.license.model.LicenseStatusModel;
import com.jw.ignition.license.model.LicenseVerifierConfigModel;
import com.jw.ignition.license.model.MachineInfoRequestModel;
import com.jw.ignition.license.model.MachineInfoSnapshotModel;
import com.jw.ignition.license.state.CachedLicenseState;
import com.jw.ignition.license.upload.LicenseMultipartUploadParser;
import com.jw.ignition.license.upload.LicenseUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ModuleLicenseService {
    private static final Logger LOG = LoggerFactory.getLogger(ModuleLicenseService.class);

    private final String moduleId;
    private final String mountAlias;
    private final LicenseFileStoreService fileStore;
    private final MachineInfoService machineInfo;
    private final LicenseVerifierService verifier;
    private final LicenseVerifierConfigModel verifierConfig;
    private final CachedLicenseState cachedState;
    private final LicenseAuditService audit;
    private final LicenseMultipartUploadParser uploadParser = new LicenseMultipartUploadParser();

    public ModuleLicenseService(GatewayContext context, String moduleId, String mountAlias) {
        this(context, moduleId, mountAlias, RsaPublicKeys.getPublicKey());
    }

    public ModuleLicenseService(GatewayContext context, String moduleId, String mountAlias, PublicKey publicKey) {
        this.moduleId = requireText(moduleId, "Module id is required.");
        this.mountAlias = requireText(mountAlias, "Module mount alias is required.");
        this.fileStore = new LicenseFileStoreService(context, this.mountAlias);
        this.machineInfo = new MachineInfoService();
        this.verifier = new LicenseVerifierService(fileStore, machineInfo);
        this.verifierConfig = verifierConfig(publicKey);
        this.audit = new LicenseAuditService();
        this.cachedState = new CachedLicenseState(verifier, verifierConfig, audit);
    }

    // validate current license
    public LicenseStatusModel currentStatus() {
        return cachedState.current();
    }

    public boolean isLicensed() {
        return cachedState.isLicensed();
    }

    public LicenseStatusModel requireLicensed() {
        LicenseStatusModel status = currentStatus();
        if (!status.licensed) {
            throw new LicenseAccessException(status);
        }
        return status;
    }

    public boolean hasFeature(String feature) {
        return hasFeature(currentStatus(), feature);
    }

    public LicenseStatusModel requireFeature(String feature) {
        LicenseStatusModel status = requireLicensed();
        String normalizedFeature = normalizeFeature(feature);
        if (!hasFeature(status, normalizedFeature)) {
            throw new LicenseFeatureAccessException(List.of(normalizedFeature), status);
        }
        return status;
    }

    public LicenseStatusModel requireAnyFeature(String... features) {
        LicenseStatusModel status = requireLicensed();
        List<String> normalizedFeatures = normalizeFeatures(features);
        if (normalizedFeatures.stream().noneMatch(feature -> hasFeature(status, feature))) {
            throw new LicenseFeatureAccessException(normalizedFeatures, status);
        }
        return status;
    }

    // revalidate license
    public LicenseStatusModel revalidate() {
        return cachedState.revalidate();
    }

    public LicenseStatusModel revalidate(String reason) {
        try {
            LicenseStatusModel status = revalidate();
            audit.revalidationCompleted(reason, status);
            return status;
        } catch (RuntimeException error) {
            audit.revalidationFailed(reason, error);
            throw error;
        }
    }

    // get machine information for file generation
    public MachineInfoRequestModel machineRequest() {
        MachineInfoSnapshotModel snapshot = machineInfo.collect();
        return machineInfo.createRequest(snapshot);
    }

    public String machineRequestFile() {
        return machineInfo.generateRequestFile(machineRequest());
    }

    // handle file uploaded through gateway
    public LicenseStatusModel upload(LicenseUpload upload) {
        Objects.requireNonNull(upload, "License upload is required.");
        return upload(upload.content());
    }

    // validate file before actually storing the file at Ignition installation directory
    public LicenseStatusModel upload(byte[] content) {
        Objects.requireNonNull(content, "License content is required.");
        if (content.length == 0) {
            LOG.warn("License upload rejected. code={} message={} licensePath={} sizeBytes={}.",
                "BAD_REQUEST", "License file is empty.", fileStore.licensePath(), content.length);
            audit.uploadRejected("empty_file", null, fileStore.licensePath(), content.length);
            throw new LicenseUploadException(400, "BAD_REQUEST", "License file is empty.");
        }
        if (content.length > LicenseMultipartUploadParser.MAX_LICENSE_FILE_BYTES) {
            LOG.warn("License upload rejected. code={} message={} licensePath={} sizeBytes={}.",
                "LICENSE_FILE_TOO_LARGE", "License file must be 1 MB or smaller.", fileStore.licensePath(), content.length);
            audit.uploadRejected("file_too_large", null, fileStore.licensePath(), content.length);
            throw new LicenseUploadException(413, "LICENSE_FILE_TOO_LARGE", "License file must be 1 MB or smaller.");
        }

        String json = new String(content, StandardCharsets.UTF_8);
        LicenseStatusModel candidate = verifier.verifyContent(json, verifierConfig);
        if (!candidate.licensed) {
            logUploadRejected(candidate, content.length);
            audit.uploadRejected("verification_failed", candidate, fileStore.licensePath(), content.length);
            return candidate;
        }

        fileStore.write(content);
        LOG.info(
            "License uploaded. licenseId={} client={} moduleId={} features={} expiresAt={} licensePath={} sizeBytes={}.",
            value(candidate.licenseId),
            value(candidate.client),
            value(candidate.moduleId),
            candidate.features,
            value(candidate.expiresAt),
            fileStore.licensePath(),
            content.length
        );
        audit.uploadAccepted(candidate, fileStore.licensePath(), content.length);
        return revalidate("upload");
    }

    public LicenseMultipartUploadParser uploadParser() {
        return uploadParser;
    }

    public CachedLicenseState cachedState() {
        return cachedState;
    }

    public int maxLicenseFileBytes() {
        return LicenseMultipartUploadParser.MAX_LICENSE_FILE_BYTES;
    }

    // license verifier config
    private LicenseVerifierConfigModel verifierConfig(PublicKey publicKey) {
        LicenseVerifierConfigModel config = new LicenseVerifierConfigModel();
        config.moduleId = moduleId;
        config.publicKey = publicKey;
        config.minimumMatchRatio = 0.5d;
        config.minimumLicenseIdentifiers = 1;
        return config;
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void logUploadRejected(LicenseStatusModel status, int sizeBytes) {
        LicenseStatusCode code = status == null ? null : status.code;
        if (code == LicenseStatusCode.INVALID_SIGNATURE) {
            LOG.warn(
                "License upload rejected: signature invalid. licenseId={} moduleId={} licensePath={} sizeBytes={} message={}.",
                value(status.licenseId),
                value(status.moduleId),
                fileStore.licensePath(),
                sizeBytes,
                value(status.message)
            );
            return;
        }

        if (code == LicenseStatusCode.WRONG_MODULE) {
            LOG.warn(
                "License upload rejected: module mismatch. licenseId={} expectedModuleId={} actualModuleId={} licensePath={} sizeBytes={}.",
                value(status.licenseId),
                value(verifierConfig.moduleId),
                value(status.moduleId),
                fileStore.licensePath(),
                sizeBytes
            );
            return;
        }

        if (code == LicenseStatusCode.EXPIRED) {
            LOG.warn(
                "License upload rejected: license expired. licenseId={} client={} moduleId={} expiresAt={} licensePath={} sizeBytes={}.",
                value(status.licenseId),
                value(status.client),
                value(status.moduleId),
                value(status.expiresAt),
                fileStore.licensePath(),
                sizeBytes
            );
            return;
        }

        if (code == LicenseStatusCode.MACHINE_MISMATCH) {
            LOG.warn(
                "License upload rejected: machine mismatch. licenseId={} client={} moduleId={} matchedIdentifiers={} requiredMatchedIdentifiers={} licensePath={} sizeBytes={}.",
                value(status.licenseId),
                value(status.client),
                value(status.moduleId),
                status.matchedIdentifiers,
                status.requiredMatchedIdentifiers,
                fileStore.licensePath(),
                sizeBytes
            );
            return;
        }

        LOG.warn(
            "License upload rejected. code={} message={} licenseId={} moduleId={} licensePath={} sizeBytes={}.",
            code,
            status == null ? "" : value(status.message),
            status == null ? "" : value(status.licenseId),
            status == null ? "" : value(status.moduleId),
            fileStore.licensePath(),
            sizeBytes
        );
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private boolean hasFeature(LicenseStatusModel status, String feature) {
        if (status == null || !status.licensed) {
            return false;
        }
        String normalizedFeature = normalizeFeature(feature);
        return status.features != null && status.features.stream()
            .map(this::normalizeFeature)
            .anyMatch(normalizedFeature::equals);
    }

    private String normalizeFeature(String feature) {
        if (feature == null || feature.trim().isBlank()) {
            throw new IllegalArgumentException("License feature key is required.");
        }
        return feature.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizeFeatures(String... features) {
        if (features == null || features.length == 0) {
            throw new IllegalArgumentException("At least one license feature key is required.");
        }
        List<String> normalized = new ArrayList<>();
        for (String feature : features) {
            String value = normalizeFeature(feature);
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }
}
