package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

/** Binds a UDP endpoint; the returned handle releases the port when closed. */
@FunctionalInterface
public interface ListenerBinder {
    Closeable bind(InetAddress address, int port) throws IOException;
}
