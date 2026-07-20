package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.jw.ignition.common.license.LicenseRequirement;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.snmpdriver.snmp.address.NumericOid;
import org.snmp4j.smi.Variable;

public final class LicensedSnmpRequestClient implements SnmpRequestClient {
    private static final LicenseRequirement LICENSED = LicenseRequirement.licensed();

    private final SnmpRequestClient delegate;
    private final ModuleLicenseRuntime license;

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
            license.require(LICENSED);
        } catch (RuntimeException error) {
            throw new SnmpLicenseException(
                    "SNMP operation blocked because SNMP Driver is not licensed",
                    error
            );
        }
    }
}

final class SnmpLicenseException extends RuntimeException {
    SnmpLicenseException(String message, Throwable cause) {
        super(message, cause);
    }
}
