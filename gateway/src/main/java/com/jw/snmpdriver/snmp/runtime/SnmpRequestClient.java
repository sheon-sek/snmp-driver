package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionStage;

import com.jw.snmpdriver.snmp.address.NumericOid;
import org.snmp4j.smi.Variable;

public interface SnmpRequestClient extends AutoCloseable {
    CompletionStage<BatchResult> getBatch(SnmpEndpoint endpoint, List<NumericOid> oids);

    CompletionStage<SetResult> set(SnmpEndpoint endpoint, NumericOid oid, Variable value);

    void start() throws IOException;

    @Override
    void close();
}

