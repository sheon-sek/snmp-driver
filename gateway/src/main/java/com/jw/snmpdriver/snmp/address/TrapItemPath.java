package com.jw.snmpdriver.snmp.address;

import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * {@code TRAP.<Field>/<trapOID>}: read-only per-trapOID Notification state. It never maps to an SNMP GET.
 */
public record TrapItemPath(Field field, NumericOid trapOid) {
    public enum Field {
        COUNT("Count", Identifiers.UInt64),
        LAST_TIME("LastTime", Identifiers.DateTime),
        LAST_VARBINDS("LastVarbinds", Identifiers.String),
        SEQUENCE("Sequence", Identifiers.UInt64);

        private final String token;
        private final NodeId dataType;

        Field(String token, NodeId dataType) {
            this.token = token;
            this.dataType = dataType;
        }

        public String token() {
            return token;
        }

        public NodeId dataType() {
            return dataType;
        }
    }

    public String canonical() {
        return "TRAP." + field.token + "/" + trapOid.canonical();
    }
}
