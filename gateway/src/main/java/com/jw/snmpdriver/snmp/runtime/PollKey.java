package com.jw.snmpdriver.snmp.runtime;

import com.jw.snmpdriver.snmp.address.NumericOid;

public record PollKey(String deviceId, NumericOid oid) {
}

