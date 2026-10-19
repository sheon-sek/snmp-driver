package com.jw.snmpdriver.snmp.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.jw.snmpdriver.snmp.address.NumericOid;
import org.snmp4j.smi.Variable;

/** Captures requests at the network boundary; tests decide when and how they complete. */
final class FakeSnmpRequestClient implements SnmpRequestClient {
    final List<GetRequest> gets = new ArrayList<>();
    final List<SetRequest> sets = new ArrayList<>();
    RuntimeException nextGetFailure;

    @Override
    public CompletionStage<BatchResult> getBatch(SnmpEndpoint endpoint, List<NumericOid> oids) {
        GetRequest request = new GetRequest(endpoint, List.copyOf(oids), new CompletableFuture<>());
        gets.add(request);
        if (nextGetFailure != null) {
            RuntimeException error = nextGetFailure;
            nextGetFailure = null;
            throw error;
        }
        return request.result();
    }

    @Override
    public CompletionStage<SetResult> set(SnmpEndpoint endpoint, NumericOid oid, Variable value) {
        SetRequest request = new SetRequest(endpoint, oid, value, new CompletableFuture<>());
        sets.add(request);
        return request.result();
    }

    @Override
    public void start() {}

    @Override
    public void close() {}

    record GetRequest(SnmpEndpoint endpoint, List<NumericOid> oids, CompletableFuture<BatchResult> result) {}
    record SetRequest(SnmpEndpoint endpoint, NumericOid oid, Variable value, CompletableFuture<SetResult> result) {}
}
