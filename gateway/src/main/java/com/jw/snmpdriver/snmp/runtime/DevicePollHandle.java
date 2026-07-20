package com.jw.snmpdriver.snmp.runtime;

import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;

import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import org.snmp4j.smi.Variable;

public interface DevicePollHandle extends AutoCloseable {
    PollSubscription subscribe(
            ResolvedAddress address,
            long revisedIntervalMillis,
            Consumer<RawBinding> onValue,
            Consumer<Throwable> onFailure
    );

    CompletionStage<SetResult> set(
            ResolvedAddress address,
            Variable value,
            long deadlineNanos
    );

    DevicePollSnapshot snapshot();

    @Override
    void close();
}

