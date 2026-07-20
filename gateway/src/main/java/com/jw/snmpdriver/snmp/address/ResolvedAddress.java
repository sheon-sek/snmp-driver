package com.jw.snmpdriver.snmp.address;

public record ResolvedAddress(
        NumericOid oid,
        SnmpWireType wireType,
        ParsedItemPath.Access access,
        boolean stringFallback,
        String canonicalItemPath
) {
    public boolean writableCandidate() {
        return access == ParsedItemPath.Access.READ_WRITE && !stringFallback;
    }

    public String symbol() {
        return oid.canonical();
    }

    public String description() {
        return stringFallback
                ? "Numeric OID exposed as a read-only String fallback."
                : access == ParsedItemPath.Access.READ_WRITE
                        ? "Explicitly typed numeric OID with requested read/write access."
                        : "Explicitly typed read-only numeric OID.";
    }

    public String itemPath() {
        return canonicalItemPath;
    }
}

