package com.jw.ignition.license.audit;

import com.jw.ignition.license.model.LicenseStatusModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class LicenseAuditService {
    private static final Logger LOG = LoggerFactory.getLogger(LicenseAuditService.class);

    public void uploadAccepted(LicenseStatusModel status, Path licensePath, int sizeBytes) {
        LOG.info(
            "License audit event=upload_accepted licenseId={} client={} moduleId={} features={} expiresAt={} licensePath={} sizeBytes={}.",
            value(status == null ? null : status.licenseId),
            value(status == null ? null : status.client),
            value(status == null ? null : status.moduleId),
            status == null ? "" : status.features,
            value(status == null ? null : status.expiresAt),
            value(licensePath),
            sizeBytes
        );
    }

    public void uploadRejected(String reason, LicenseStatusModel status, Path licensePath, int sizeBytes) {
        LOG.warn(
            "License audit event=upload_rejected reason={} code={} message={} licenseId={} moduleId={} licensePath={} sizeBytes={}.",
            value(reason),
            status == null ? "" : status.code,
            value(status == null ? null : status.message),
            value(status == null ? null : status.licenseId),
            value(status == null ? null : status.moduleId),
            value(licensePath),
            sizeBytes
        );
    }

    public void validationTransition(LicenseStatusModel previous, LicenseStatusModel currentStatus) {
        if (currentStatus == null || sameStatusEvent(previous, currentStatus)) {
            return;
        }

        if (currentStatus.licensed) {
            LOG.info(
                "License audit event=validation_accept code={} licenseId={} client={} moduleId={} features={} expiresAt={} matchedIdentifiers={} requiredMatchedIdentifiers={} licensePath={}.",
                currentStatus.code,
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

        LOG.warn(
            "License audit event=validation_reject code={} message={} licenseId={} moduleId={} licensePath={}.",
            currentStatus.code,
            value(currentStatus.message),
            value(currentStatus.licenseId),
            value(currentStatus.moduleId),
            value(currentStatus.licensePath)
        );
    }

    public void revalidationCompleted(String reason, LicenseStatusModel status) {
        LOG.info(
            "License audit event=revalidation_completed reason={} licensed={} code={} licenseId={} moduleId={} licensePath={}.",
            value(reason),
            status != null && status.licensed,
            status == null ? "" : status.code,
            value(status == null ? null : status.licenseId),
            value(status == null ? null : status.moduleId),
            value(status == null ? null : status.licensePath)
        );
    }

    public void revalidationFailed(String reason, RuntimeException error) {
        LOG.warn(
            "License audit event=revalidation_failed reason={} error={}.",
            value(reason),
            error == null ? "" : value(error.getMessage())
        );
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
            + "|" + value(status.client)
            + "|" + value(status.moduleId)
            + "|" + status.features
            + "|" + value(status.expiresAt)
            + "|" + status.matchedIdentifiers
            + "|" + status.requiredMatchedIdentifiers
            + "|" + value(status.message);
    }

    private String value(Path value) {
        return value == null ? "" : value.toString();
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
