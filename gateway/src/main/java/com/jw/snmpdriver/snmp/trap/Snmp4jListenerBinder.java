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
import org.snmp4j.event.AuthenticationFailureEvent;
import org.snmp4j.event.AuthenticationFailureListener;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Real bind with a private dispatcher (v1/v2c, plus v3 through the Listener's own {@link TrapUsmStack}) and our
 * own responder. {@code addNotificationListener}
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
        return bind(address, port, handler, null);
    }

    @Override
    public Closeable bind(InetAddress address, int port, NotificationHandler handler, TrapUsmStack usm)
            throws IOException {
        DefaultUdpTransportMapping transport =
                new DefaultUdpTransportMapping(new UdpAddress(address, port), false);
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        if (usm != null) {
            dispatcher.addMessageProcessingModel(usm.mpv3());
            dispatcher.addAuthenticationFailureListener(new AuthenticationFailureListener() {
                @Override
                public <A extends Address> void authenticationFailure(AuthenticationFailureEvent<A> event) {
                    TrapUsmStack.Rejection rejection = usm.takeRejection();
                    if (rejection != TrapUsmStack.Rejection.DISCOVERY
                            && event.getAddress() instanceof UdpAddress peer) {
                        handler.onAuthenticationFailure(peer.getInetAddress(), event.getError(),
                                rejection == TrapUsmStack.Rejection.LOCAL_ENGINE);
                    }
                }
            });
        }
        Snmp snmp = new Snmp(dispatcher, transport);
        snmp.addCommandResponder(new CommandResponder() {
            @Override
            public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                dispatch(event, handler, usm);
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

    private static void dispatch(CommandResponderEvent<?> event, NotificationHandler handler, TrapUsmStack usm) {
        PDU pdu = event.getPDU();
        if (pdu == null || !(event.getPeerAddress() instanceof UdpAddress peer)) {
            return;
        }
        int type = pdu.getType();
        if (type != PDU.V1TRAP && type != PDU.TRAP && type != PDU.INFORM) {
            return;
        }
        event.setProcessed(true);
        int model = event.getMessageProcessingModel();
        TrapNotification.Version version = model == MPv1.ID ? TrapNotification.Version.V1
                : model == MPv3.ID ? TrapNotification.Version.V3 : TrapNotification.Version.V2C;
        // a v3 Inform's RESPONSE goes back under the Gateway engine it was authenticated with
        Runnable ack = type == PDU.INFORM ? () -> acknowledge(event, pdu) : null;
        byte[] engine = version == TrapNotification.Version.V3 && usm != null ? usm.takeIncomingEngineId() : null;
        handler.onNotification(new IncomingNotification(
                peer.getInetAddress(), peer.getPort(), version, event.getSecurityName(), pdu, ack,
                engine, event.getSecurityLevel()));
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
