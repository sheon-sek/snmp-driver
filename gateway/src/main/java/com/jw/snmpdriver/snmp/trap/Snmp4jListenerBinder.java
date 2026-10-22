package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Real bind with a private v1/v2c dispatcher and our own responder. {@code addNotificationListener}
 * is deliberately not used: it ACKs an Inform before we have decided anything (ADR 0004).
 */
public final class Snmp4jListenerBinder implements ListenerBinder {
    @Override
    public Closeable bind(InetAddress address, int port) throws IOException {
        return bind(address, port, notification -> { });
    }

    @Override
    public Closeable bind(InetAddress address, int port, NotificationHandler handler) throws IOException {
        DefaultUdpTransportMapping transport =
                new DefaultUdpTransportMapping(new UdpAddress(address, port), false);
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        Snmp snmp = new Snmp(dispatcher, transport);
        snmp.addCommandResponder(new CommandResponder() {
            @Override
            public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                dispatch(event, handler);
            }
        });
        try {
            snmp.listen();
        } catch (IOException | RuntimeException error) {
            snmp.close();
            throw error;
        }
        return snmp;
    }

    private static void dispatch(CommandResponderEvent<?> event, NotificationHandler handler) {
        PDU pdu = event.getPDU();
        if (pdu == null || !(event.getPeerAddress() instanceof UdpAddress peer)) {
            return;
        }
        int type = pdu.getType();
        if (type != PDU.V1TRAP && type != PDU.TRAP && type != PDU.INFORM) {
            return;
        }
        event.setProcessed(true);
        TrapNotification.Version version = event.getMessageProcessingModel() == MPv1.ID
                ? TrapNotification.Version.V1 : TrapNotification.Version.V2C;
        handler.onNotification(new IncomingNotification(
                peer.getInetAddress(), peer.getPort(), version, event.getSecurityName(), pdu));
    }
}
