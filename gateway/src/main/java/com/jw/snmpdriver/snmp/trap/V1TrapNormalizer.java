package com.jw.snmpdriver.snmp.trap;

import java.util.ArrayList;
import java.util.List;

import org.snmp4j.PDUv1;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

/** RFC 3584 §3.1 conversion of a v1 Trap PDU; the community is deliberately not carried over. */
final class V1TrapNormalizer {
    static final OID SYS_UP_TIME = new OID("1.3.6.1.2.1.1.3.0");
    static final OID SNMP_TRAP_OID = new OID("1.3.6.1.6.3.1.1.4.1.0");
    static final OID SNMP_TRAP_ADDRESS = new OID("1.3.6.1.6.3.18.1.3.0");
    static final OID SNMP_TRAP_COMMUNITY = new OID("1.3.6.1.6.3.18.1.4.0");
    static final OID SNMP_TRAP_ENTERPRISE = new OID("1.3.6.1.6.3.1.1.4.3.0");
    private static final String GENERIC_PREFIX = "1.3.6.1.6.3.1.1.5.";

    private V1TrapNormalizer() {
    }

    /** A forwarder may already carry snmpTrapCommunity.0; it must never reach a Tag. */
    static List<VariableBinding> withoutCommunity(List<? extends VariableBinding> varbinds) {
        List<VariableBinding> kept = new ArrayList<>(varbinds.size());
        for (VariableBinding varbind : varbinds) {
            if (!SNMP_TRAP_COMMUNITY.equals(varbind.getOid())) {
                kept.add(varbind);
            }
        }
        return kept;
    }

    static OID trapOid(PDUv1 pdu) {
        int generic = pdu.getGenericTrap();
        if (generic >= 0 && generic <= 5) {
            return new OID(GENERIC_PREFIX + (generic + 1));
        }
        OID oid = new OID(pdu.getEnterprise());
        oid.append(0);
        oid.append(pdu.getSpecificTrap());
        return oid;
    }

    static List<VariableBinding> normalize(PDUv1 pdu) {
        List<VariableBinding> result = new ArrayList<>(pdu.size() + 4);
        result.add(new VariableBinding(SYS_UP_TIME, new TimeTicks(pdu.getTimestamp())));
        result.add(new VariableBinding(SNMP_TRAP_OID, trapOid(pdu)));
        for (VariableBinding original : withoutCommunity(pdu.getAll())) {
            result.add(new VariableBinding(original.getOid(), original.getVariable()));
        }
        result.add(new VariableBinding(SNMP_TRAP_ADDRESS, pdu.getAgentAddress()));
        result.add(new VariableBinding(SNMP_TRAP_ENTERPRISE, pdu.getEnterprise()));
        return result;
    }
}
