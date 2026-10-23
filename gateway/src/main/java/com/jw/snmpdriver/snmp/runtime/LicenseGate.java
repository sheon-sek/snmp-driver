package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Supplier;

import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;

/** Side-effect free License verdict shared by polling and Trap; the status model is cached per instance. */
public final class LicenseGate {
    /** PENDING: the License has not been verified yet since module startup. */
    public enum Verdict { LICENSED, PENDING, NOT_AVAILABLE, EXPIRED }

    private final Supplier<LicenseStatusModel> license;
    private volatile CachedAccess cachedAccess = CachedAccess.empty();

    public LicenseGate(ModuleLicenseRuntime license) {
        Objects.requireNonNull(license);
        this.license = () -> license.service().currentStatus();
    }

    public LicenseGate(Supplier<LicenseStatusModel> license) {
        this.license = Objects.requireNonNull(license);
    }

    /** Never throws; an unreadable License counts as not available. */
    public Verdict check() {
        try {
            LicenseStatusModel status = license.get();
            if (status != null && status.code == LicenseStatusCode.UNVERIFIED) {
                return Verdict.PENDING;
            }
            CachedAccess access = cachedAccess;
            if (access.status() != status) {
                access = updateCachedAccess(status);
            }
            long nowMillis = System.currentTimeMillis();
            if (access.allows(nowMillis)) {
                return Verdict.LICENSED;
            }
            return access.expired(nowMillis) ? Verdict.EXPIRED : Verdict.NOT_AVAILABLE;
        } catch (RuntimeException error) {
            return Verdict.NOT_AVAILABLE;
        }
    }

    private synchronized CachedAccess updateCachedAccess(LicenseStatusModel status) {
        CachedAccess current = cachedAccess;
        if (current.status() == status) {
            return current;
        }
        CachedAccess updated = new CachedAccess(
                status,
                status != null && status.licensed,
                expirationTime(status)
        );
        cachedAccess = updated;
        return updated;
    }

    private static long expirationTime(LicenseStatusModel status) {
        if (status == null || status.expiresAt == null || status.expiresAt.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            return Instant.parse(status.expiresAt.trim()).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private record CachedAccess(
            LicenseStatusModel status,
            boolean licensed,
            long expiresAtMillis
    ) {
        private static CachedAccess empty() {
            return new CachedAccess(null, false, 0L);
        }

        private boolean allows(long nowMillis) {
            return licensed
                    && expiresAtMillis != Long.MIN_VALUE
                    && nowMillis < expiresAtMillis;
        }

        private boolean expired(long nowMillis) {
            return status != null
                    && (status.code == LicenseStatusCode.EXPIRED
                    || (licensed
                    && expiresAtMillis != Long.MIN_VALUE
                    && expiresAtMillis != Long.MAX_VALUE
                    && nowMillis >= expiresAtMillis));
        }
    }
}
