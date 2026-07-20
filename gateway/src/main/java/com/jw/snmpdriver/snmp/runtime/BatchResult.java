package com.jw.snmpdriver.snmp.runtime;

import java.util.List;

public record BatchResult(
        List<RawBinding> bindings,
        int errorStatus,
        int errorIndex,
        String errorText
) {
    public BatchResult {
        bindings = List.copyOf(bindings);
        errorText = errorText == null ? "" : errorText;
    }

    public boolean successful() {
        return errorStatus == 0;
    }
}

