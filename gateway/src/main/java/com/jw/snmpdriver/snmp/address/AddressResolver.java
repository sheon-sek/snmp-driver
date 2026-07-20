package com.jw.snmpdriver.snmp.address;

public final class AddressResolver {
    private final ItemPathParser parser;

    public AddressResolver() {
        this(new ItemPathParser());
    }

    public AddressResolver(ItemPathParser parser) {
        this.parser = parser;
    }

    public ResolvedAddress resolve(String identifier) {
        ParsedItemPath parsed = parser.parse(identifier);
        return new ResolvedAddress(
                parsed.oid(),
                parsed.explicitType().orElse(SnmpWireType.STRING_FALLBACK),
                parsed.access(),
                parsed.stringFallback(),
                parsed.canonical()
        );
    }
}

