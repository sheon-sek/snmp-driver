package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.snmp4j.PDUv1;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

class V1TrapNormalizerTest {
    private static PDUv1 v1(int generic, int specific) {
        PDUv1 pdu = new PDUv1();
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9999"));
        pdu.setAgentAddress(new IpAddress("10.0.0.7"));
        pdu.setGenericTrap(generic);
        pdu.setSpecificTrap(specific);
        pdu.setTimestamp(4242);
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.3"), new OctetString("eth3")));
        return pdu;
    }

    @Test
    void genericTrapsMapToStandardNotificationOids() {
        for (int generic = 0; generic <= 5; generic++) {
            assertEquals(new OID("1.3.6.1.6.3.1.1.5." + (generic + 1)), V1TrapNormalizer.trapOid(v1(generic, 0)));
        }
    }

    @Test
    void enterpriseSpecificTrapUsesEnterpriseZeroSpecific() {
        assertEquals(new OID("1.3.6.1.4.1.9999.0.17"), V1TrapNormalizer.trapOid(v1(6, 17)));
    }

    @Test
    void normalizedVarbindsFollowRfc3584Order() {
        List<VariableBinding> out = V1TrapNormalizer.normalize(v1(6, 17));

        assertEquals(5, out.size());
        assertEquals(V1TrapNormalizer.SYS_UP_TIME, out.get(0).getOid());
        assertEquals(new TimeTicks(4242), out.get(0).getVariable());
        assertEquals(V1TrapNormalizer.SNMP_TRAP_OID, out.get(1).getOid());
        assertEquals(new OID("1.3.6.1.4.1.9999.0.17"), out.get(1).getVariable());
        assertEquals(new OID("1.3.6.1.2.1.2.2.1.1.3"), out.get(2).getOid());
        assertEquals(V1TrapNormalizer.SNMP_TRAP_ADDRESS, out.get(3).getOid());
        assertEquals(new IpAddress("10.0.0.7"), out.get(3).getVariable());
        assertEquals(V1TrapNormalizer.SNMP_TRAP_ENTERPRISE, out.get(4).getOid());
        assertEquals(new OID("1.3.6.1.4.1.9999"), out.get(4).getVariable());
    }

    @Test
    void noCommunityVarbindIsAdded() {
        List<VariableBinding> out = V1TrapNormalizer.normalize(v1(1, 0));

        assertEquals(new OID("1.3.6.1.6.3.1.1.5.2"), out.get(1).getVariable());
        for (VariableBinding vb : out) {
            assertTrue(!vb.getOid().equals(V1TrapNormalizer.SNMP_TRAP_COMMUNITY));
        }
    }

    @Test
    void communityVarbindAlreadyInThePduIsStripped() {
        PDUv1 pdu = v1(1, 0);
        pdu.add(new VariableBinding(V1TrapNormalizer.SNMP_TRAP_COMMUNITY, new OctetString("secret")));

        List<VariableBinding> out = V1TrapNormalizer.normalize(pdu);

        for (VariableBinding vb : out) {
            assertTrue(!vb.getOid().equals(V1TrapNormalizer.SNMP_TRAP_COMMUNITY), "community leaked: " + vb);
        }
        assertEquals(5, out.size());
    }
}
