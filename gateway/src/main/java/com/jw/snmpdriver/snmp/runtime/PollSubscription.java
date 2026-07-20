package com.jw.snmpdriver.snmp.runtime;

public interface PollSubscription extends AutoCloseable {
    @Override
    void close();
}

