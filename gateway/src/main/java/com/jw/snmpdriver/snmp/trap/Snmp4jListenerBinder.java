package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** Real bind; no message dispatcher is attached, so received datagrams are dropped. */
public final class Snmp4jListenerBinder implements ListenerBinder {
    @Override
    public Closeable bind(InetAddress address, int port) throws IOException {
        DefaultUdpTransportMapping transport =
                new DefaultUdpTransportMapping(new UdpAddress(address, port), false);
        try {
            transport.listen();
        } catch (IOException | RuntimeException error) {
            transport.close();
            throw error;
        }
        return transport;
    }
}
