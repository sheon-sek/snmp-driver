package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * #32: an OPC UA write of a V2C {@code RW.Integer32} Item must reach the agent as an SNMP SET with the Write
 * Community, answer Good, and a later poll must read the new value back.
 */
class SNMPDeviceWriteTest {
    private static final String OID_TEXT = "1.3.6.1.4.1.53864.1.0";
    private static final String ITEM = "RW.Integer32/" + OID_TEXT;
    private static final SecretConfig READ = SecretConfig.referenced("test", "read");
    private static final SecretConfig WRITE = SecretConfig.referenced("test", "write");

    @Test
    void v2cInteger32WriteSendsSetWithWriteCommunityAndReadsBack() throws Exception {
        try (Agent agent = new Agent();
             SNMPDeviceFixture fixture = new SNMPDeviceFixture(
                     config(agent.port(), SNMPDeviceConfig.AccessMode.READ_WRITE), SNMPDeviceWriteTest::plaintext)) {
            StatusCode status = fixture.writePath(ITEM, 42);

            assertEquals(StatusCode.GOOD, status);
            assertEquals(List.of("st-private"), agent.setCommunities);
            assertEquals(new Integer32(42), agent.values.get(new OID(OID_TEXT)));

            List<DataValue> published = fixture.subscribePath(ITEM, 1000.0);
            long deadline = System.currentTimeMillis() + 5_000;
            while (published.stream().noneMatch(v -> Integer.valueOf(42).equals(v.getValue().getValue()))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(published.stream().anyMatch(v -> v.getStatusCode().isGood()
                            && Integer.valueOf(42).equals(v.getValue().getValue())),
                    "poll did not read back 42: " + published);
        }
    }

    @Test
    void readOnlyDeviceRejectsWriteWithoutSendingSet() throws Exception {
        try (Agent agent = new Agent();
             SNMPDeviceFixture fixture = new SNMPDeviceFixture(
                     config(agent.port(), SNMPDeviceConfig.AccessMode.READ_ONLY), SNMPDeviceWriteTest::plaintext)) {
            assertEquals(StatusCodes.Bad_NotWritable, fixture.writePath(ITEM, 42).getValue());
            assertEquals(List.of(), agent.setCommunities);
        }
    }

    private static String plaintext(SecretConfig secret) {
        return secret == WRITE ? "st-private" : "st-public";
    }

    private static SNMPDeviceConfig config(int port, SNMPDeviceConfig.AccessMode accessMode) {
        SNMPDeviceConfig d = SNMPDeviceConfig.defaults();
        return new SNMPDeviceConfig("127.0.0.1", port, d.ipFamily(), SNMPDeviceConfig.Version.V2C,
                READ, WRITE, "", d.securityLevel(), d.authProtocol(), null, d.privacyProtocol(), null, "",
                accessMode, 1000, 1, d.maxOidsPerRequest(), d.maxRequestBytes(), d.maxInflightPerDevice(),
                false, d.trapBindAddress(), d.trapPort(), d.trapSecurity(), null, "", true, "", 100, "", "");
    }

    /** A v2c agent on loopback: GET with st-public, SET with st-private, one Integer32 starting at 5. */
    private static final class Agent implements AutoCloseable {
        final Map<OID, Variable> values = new ConcurrentHashMap<>(Map.of(new OID(OID_TEXT), new Integer32(5)));
        final List<String> setCommunities = new CopyOnWriteArrayList<>();
        private final DefaultUdpTransportMapping transport;
        private final Snmp session;

        Agent() throws IOException {
            transport = new DefaultUdpTransportMapping(new UdpAddress(InetAddress.getLoopbackAddress(), 0));
            session = new Snmp(transport);
            session.addCommandResponder(new CommandResponder() {
                @Override
                public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                    respond(event);
                }
            });
            session.listen();
        }

        int port() {
            return transport.getListenAddress().getPort();
        }

        private <A extends Address> void respond(CommandResponderEvent<A> event) {
            PDU request = event.getPDU();
            String community = new String(event.getSecurityName());
            PDU response = (PDU) request.clone();
            response.setType(PDU.RESPONSE);
            if (request.getType() == PDU.SET) {
                if (!community.equals("st-private")) {
                    response.setErrorStatus(PDU.noAccess);
                    response.setErrorIndex(1);
                } else {
                    setCommunities.add(community);
                    for (VariableBinding vb : request.getVariableBindings()) {
                        values.put(vb.getOid(), vb.getVariable());
                    }
                }
            } else if (request.getType() == PDU.GET && community.equals("st-public")) {
                for (VariableBinding vb : response.getVariableBindings()) {
                    Variable value = values.get(vb.getOid());
                    vb.setVariable(value == null ? Null.noSuchObject : value);
                }
            } else {
                return;
            }
            event.setProcessed(true);
            try {
                event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                        event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                        event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
            } catch (Exception error) {
                throw new IllegalStateException("Test agent response failed", error);
            }
        }

        @Override
        public void close() throws IOException {
            session.close();
        }
    }
}
