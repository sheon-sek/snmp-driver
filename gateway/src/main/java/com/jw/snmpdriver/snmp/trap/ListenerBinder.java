package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

/** Binds a UDP endpoint; the returned handle releases the port when closed. */
@FunctionalInterface
public interface ListenerBinder {
    Closeable bind(InetAddress address, int port) throws IOException;

    /** Binders that decode datagrams override this; the default binds and drops every packet. */
    default Closeable bind(InetAddress address, int port, NotificationHandler handler) throws IOException {
        return bind(address, port);
    }

    /**
     * Also decodes v3 with {@code usm}, the Listener's private Trap security stack. Binders without v3 support
     * keep the v1/v2c behaviour.
     */
    default Closeable bind(InetAddress address, int port, NotificationHandler handler, TrapUsmStack usm)
            throws IOException {
        return bind(address, port, handler);
    }
}
