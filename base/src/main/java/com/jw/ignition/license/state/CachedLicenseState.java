package com.jw.ignition.license.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.jw.ignition.license.audit.LicenseAuditService;
import com.jw.ignition.license.model.LicenseStatusModel;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.service.LicenseVerifierService;
import com.jw.ignition.license.model.LicenseVerifierConfigModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CachedLicenseState {
    private static final Logger LOG = LoggerFactory.getLogger(CachedLicenseState.class);

    private final LicenseVerifierService verifier;
    private final LicenseVerifierConfigModel config;
    private final LicenseAuditService audit;
    private final AtomicReference<LicenseStatusModel> current = new AtomicReference<>();
    private final ReentrantLock validationLock = new ReentrantLock();
    private volatile FileSnapshot lastFileSnapshot = FileSnapshot.unknown();

    public CachedLicenseState(LicenseVerifierService verifier, LicenseVerifierConfigModel config) {
        this(verifier, config, new LicenseAuditService());
    }

    public CachedLicenseState(LicenseVerifierService verifier, LicenseVerifierConfigModel config, LicenseAuditService audit) {
        if (verifier == null) {
            throw new IllegalArgumentException("License verifier is required.");
        }
        if (config == null) {
            throw new IllegalArgumentException("License verifier config is required.");
        }
        this.verifier = verifier;
        this.config = config;
        this.audit = audit == null ? new LicenseAuditService() : audit;
    }

    // validate current license
    public LicenseStatusModel current() {
        LicenseStatusModel status = current.get();
        return status == null ? unverifiedStatus() : status;
    }

    public boolean isLicensed() {
        return current().licensed;
    }

    // revalidate license
    public LicenseStatusModel revalidate() {
        if (validationLock.getHoldCount() >= 1) {
            throw new IllegalArgumentException("Thread have been locked once!");
        }

        if (!validationLock.tryLock()) {
            return current();
        }

        try {
            LicenseStatusModel status;
            try {
                status = verifier.verify(config);
            } catch (RuntimeException e) {
                status = failedStatus(e);
            }
            LicenseStatusModel previous = current.get();
            current.set(status);
            lastFileSnapshot = FileSnapshot.read(verifier.licensePath());
            logStatusTransition(previous, status);
            audit.validationTransition(previous, status);
            return status;
        } finally {
            validationLock.unlock();
        }
    }

    public LicenseStatusModel revalidateIfFileChanged() {
        FileSnapshot snapshot = FileSnapshot.read(verifier.licensePath());
        if (current.get() == null || !snapshot.equals(lastFileSnapshot)) {
            return revalidate();
        }
        return current();
    }

    public Path licensePath() {
        return verifier.licensePath();
    }

    private LicenseStatusModel unverifiedStatus() {
        LicenseStatusModel status = LicenseStatusModel.empty();
        status.code = LicenseStatusCode.UNVERIFIED;
        status.message = "License has not been validated yet.";
        status.verifiedAt = Instant.now(clock()).toString();
        status.licensePath = verifier.licensePath().toString();
        return status;
    }

    private LicenseStatusModel failedStatus(RuntimeException error) {
        LicenseStatusModel status = LicenseStatusModel.empty();
        status.code = LicenseStatusCode.IO_ERROR;
        status.message = "License verification failed.";
        status.verifiedAt = Instant.now(clock()).toString();
        status.licensePath = verifier.licensePath().toString();
        status.errors.add(rootMessage(error));
        return status;
    }

    private Clock clock() {
        return config.clock == null ? Clock.systemUTC() : config.clock;
    }

    private void logStatusTransition(LicenseStatusModel previous, LicenseStatusModel currentStatus) {
        if (currentStatus == null || sameStatusEvent(previous, currentStatus)) {
            return;
        }

        LicenseStatusCode code = currentStatus.code;
        if (code == LicenseStatusCode.LICENSED) {
            LOG.info(
                "License activated. licenseId={} client={} moduleId={} features={} expiresAt={} matchedIdentifiers={} requiredMatchedIdentifiers={} licensePath={}.",
                value(currentStatus.licenseId),
                value(currentStatus.client),
                value(currentStatus.moduleId),
                currentStatus.features,
                value(currentStatus.expiresAt),
                currentStatus.matchedIdentifiers,
                currentStatus.requiredMatchedIdentifiers,
                value(currentStatus.licensePath)
            );
            return;
        }

        if (code == LicenseStatusCode.MISSING_FILE) {
            LOG.warn("License missing. licensePath={}.", value(currentStatus.licensePath));
            return;
        }

        if (code == LicenseStatusCode.INVALID_SIGNATURE) {
            LOG.warn(
                "License signature invalid. licenseId={} moduleId={} licensePath={} message={}.",
                value(currentStatus.licenseId),
                value(currentStatus.moduleId),
                value(currentStatus.licensePath),
                value(currentStatus.message)
            );
            return;
        }

        if (code == LicenseStatusCode.WRONG_MODULE) {
            LOG.warn(
                "License module mismatch. licenseId={} expectedModuleId={} actualModuleId={} licensePath={}.",
                value(currentStatus.licenseId),
                value(config.moduleId),
                value(currentStatus.moduleId),
                value(currentStatus.licensePath)
            );
            return;
        }

        if (code == LicenseStatusCode.EXPIRED) {
            LOG.warn(
                "License expired. licenseId={} client={} moduleId={} expiresAt={} licensePath={}.",
                value(currentStatus.licenseId),
                value(currentStatus.client),
                value(currentStatus.moduleId),
                value(currentStatus.expiresAt),
                value(currentStatus.licensePath)
            );
            return;
        }

        if (code == LicenseStatusCode.MACHINE_MISMATCH) {
            LOG.warn(
                "License machine mismatch. licenseId={} client={} moduleId={} matchedIdentifiers={} requiredMatchedIdentifiers={} licensePath={}.",
                value(currentStatus.licenseId),
                value(currentStatus.client),
                value(currentStatus.moduleId),
                currentStatus.matchedIdentifiers,
                currentStatus.requiredMatchedIdentifiers,
                value(currentStatus.licensePath)
            );
            return;
        }

        if (!currentStatus.licensed) {
            LOG.warn(
                "License validation failed. code={} message={} licenseId={} moduleId={} licensePath={}.",
                code,
                value(currentStatus.message),
                value(currentStatus.licenseId),
                value(currentStatus.moduleId),
                value(currentStatus.licensePath)
            );
        }
    }

    private boolean sameStatusEvent(LicenseStatusModel previous, LicenseStatusModel currentStatus) {
        return eventKey(previous).equals(eventKey(currentStatus));
    }

    private String eventKey(LicenseStatusModel status) {
        if (status == null) {
            return "";
        }
        return status.code
            + "|" + value(status.licenseId)
            + "|" + value(status.moduleId)
            + "|" + value(status.expiresAt)
            + "|" + status.matchedIdentifiers
            + "|" + status.requiredMatchedIdentifiers
            + "|" + value(status.message);
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String rootMessage(Throwable error) {
        Throwable currentError = error;
        while (currentError.getCause() != null) {
            currentError = currentError.getCause();
        }
        String message = currentError.getMessage();
        return message == null || message.isBlank() ? currentError.getClass().getSimpleName() : message;
    }

    private record FileSnapshot(boolean exists, long lastModifiedMillis, long sizeBytes) {
        private static FileSnapshot unknown() {
            return new FileSnapshot(false, -1L, -1L);
        }

        private static FileSnapshot read(Path path) {
            if (path == null || !Files.exists(path)) {
                return new FileSnapshot(false, -1L, -1L);
            }
            try {
                return new FileSnapshot(
                    true,
                    Files.getLastModifiedTime(path).toMillis(),
                    Files.size(path)
                );
            } catch (IOException | SecurityException e) {
                return new FileSnapshot(true, -1L, -1L);
            }
        }
    }
}
