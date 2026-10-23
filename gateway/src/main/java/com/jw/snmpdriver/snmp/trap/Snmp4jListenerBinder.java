package com.jw.snmpdriver.snmp.trap;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Real bind with a private v1/v2c dispatcher and our own responder. {@code addNotificationListener}
 * is deliberately not used: it ACKs an Inform before we have decided anything (ADR 0004).
 */
public final class Snmp4jListenerBinder implements ListenerBinder {
    private static final Logger logger = LoggerFactory.getLogger(Snmp4jListenerBinder.class);

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
                peer.getInetAddress(), peer.getPort(), version, event.getSecurityName(), pdu,
                type == PDU.INFORM ? () -> acknowledge(event, pdu) : null));
    }

    /** The RESPONSE echoes the Inform's request-id and varbinds, sent back with its own security parameters. */
    private static void acknowledge(CommandResponderEvent<?> event, PDU inform) {
        PDU response = (PDU) inform.clone();
        response.setType(PDU.RESPONSE);
        response.setErrorStatus(0);
        response.setErrorIndex(0);
        try {
            event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                    event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                    event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
        } catch (MessageException | RuntimeException error) {
            logger.warn("Failed to ACK Inform peer={} category=trap", event.getPeerAddress(), error);
        }
    }
}
