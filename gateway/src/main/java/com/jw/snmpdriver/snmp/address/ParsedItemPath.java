package com.jw.snmpdriver.snmp.address;

import java.util.Optional;

public record ParsedItemPath(Access access, Optional<SnmpWireType> explicitType, NumericOid oid) {
    public enum Access { READ_ONLY, READ_WRITE }

    public ParsedItemPath {
        access = access == null ? Access.READ_ONLY : access;
        explicitType = explicitType == null ? Optional.empty() : explicitType;
    }

    public boolean stringFallback() {
        return explicitType.isEmpty();
    }

    public String canonical() {
        if (explicitType.isEmpty()) {
            return oid.canonical();
        }
        String prefix = access == Access.READ_WRITE ? "RW." : "RO.";
        return prefix + explicitType.get().canonicalToken() + "/" + oid.canonical();
    }
}

