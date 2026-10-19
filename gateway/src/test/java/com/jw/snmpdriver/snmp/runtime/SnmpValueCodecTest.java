package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;

import com.jw.snmpdriver.snmp.address.NumericOid;
import com.jw.snmpdriver.snmp.address.ParsedItemPath;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.address.SnmpWireType;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnmpValueCodecTest {
    private static final NumericOid OID = NumericOid.parse("1.3.6.1.2.1.1.3.0");

    @Test
    void sourceTimeEqualsReceivedAt() {
        Instant receivedAt = Instant.parse("2020-06-15T12:00:00Z");
        Integer32 variable = new Integer32(42);
        RawBinding binding = new RawBinding(OID, variable.getSyntax(), variable, receivedAt);

        DataValue value = new SnmpValueCodec().toDataValue(address(OID), binding);

        assertEquals(new Variant(42), value.getValue());
        assertEquals(StatusCode.GOOD, value.getStatusCode());
        assertEquals(new DateTime(receivedAt), value.getSourceTime(),
                "sourceTime must be the RawBinding receive instant, not the publish instant");
        assertNotNull(value.getServerTime());
        assertTrue(value.getServerTime().getJavaInstant().isAfter(receivedAt),
                "serverTime is the publish instant and must trail the fixed receive instant");
    }

    private static ResolvedAddress address(NumericOid oid) {
        return new ResolvedAddress(oid, SnmpWireType.INTEGER32, ParsedItemPath.Access.READ_WRITE,
                false, oid.canonical());
    }
}
