package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignition.license.enums.LicenseStatusCode;
import com.jw.ignition.license.model.LicenseStatusModel;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.snmp4j.smi.Variable;

public final class LicensedSnmpRequestClient implements SnmpRequestClient {
    private final SnmpRequestClient delegate;
    private final ModuleLicenseRuntime license;
    private volatile CachedAccess cachedAccess = CachedAccess.empty();

    public LicensedSnmpRequestClient(
            SnmpRequestClient delegate,
            ModuleLicenseRuntime license
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.license = Objects.requireNonNull(license);
    }

    @Override
    public CompletionStage<BatchResult> getBatch(
            SnmpEndpoint endpoint,
            List<NumericOid> oids
    ) {
        try {
            requireLicensed();
            return delegate.getBatch(endpoint, oids);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletionStage<SetResult> set(
            SnmpEndpoint endpoint,
            NumericOid oid,
            Variable value
    ) {
        try {
            requireLicensed();
            return delegate.set(endpoint, oid, value);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public void start() throws IOException {
        delegate.start();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private void requireLicensed() {
        try {
            LicenseStatusModel status = license.service().currentStatus();
            CachedAccess access = cachedAccess;
            if (access.status() != status) {
                access = updateCachedAccess(status);
            }
            long nowMillis = System.currentTimeMillis();
            if (!access.allows(nowMillis)) {
                boolean expired = access.expired(nowMillis);
                throw new SnmpLicenseException(
                        expired
                                ? "SNMP operation blocked because SNMP Driver license is expired"
                                : "SNMP operation blocked because SNMP Driver license is not available",
                        expired
                                ? StatusCodes.Bad_LicenseExpired
                                : StatusCodes.Bad_LicenseNotAvailable
                );
            }
        } catch (RuntimeException error) {
            if (error instanceof SnmpLicenseException licenseError) {
                throw licenseError;
            }
            throw new SnmpLicenseException(
                    "SNMP operation blocked because SNMP Driver license is not available",
                    StatusCodes.Bad_LicenseNotAvailable,
                    error
            );
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

final class SnmpLicenseException extends RuntimeException {
    private final long statusCode;

    SnmpLicenseException(String message, long statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    SnmpLicenseException(String message, long statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    long statusCode() {
        return statusCode;
    }
}
