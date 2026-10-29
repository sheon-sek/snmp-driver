package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.jw.snmpdriver.snmp.address.NumericOid;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.snmp4j.smi.Variable;

public final class LicensedSnmpRequestClient implements SnmpRequestClient {
    private final SnmpRequestClient delegate;
    private final LicenseGate gate;

    public LicensedSnmpRequestClient(
            SnmpRequestClient delegate,
            LicenseGate gate
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.gate = Objects.requireNonNull(gate);
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
        LicenseGate.Verdict verdict = gate.check();
        if (verdict == LicenseGate.Verdict.LICENSED) {
            return;
        }
        if (verdict == LicenseGate.Verdict.PENDING) {
            // Not a License failure: Status must not flash Unlicensed while startup verification runs
            throw new SnmpValueException(
                    StatusCodes.Bad_WaitingForInitialData,
                    "license-pending",
                    "SNMP operation deferred until SNMP Driver license verification completes"
            );
        }
        boolean expired = verdict == LicenseGate.Verdict.EXPIRED;
        throw new SnmpLicenseException(
                expired
                        ? "SNMP operation blocked because SNMP Driver license is expired"
                        : "SNMP operation blocked because SNMP Driver license is not available",
                expired
                        ? StatusCodes.Bad_LicenseExpired
                        : StatusCodes.Bad_LicenseNotAvailable
        );
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
