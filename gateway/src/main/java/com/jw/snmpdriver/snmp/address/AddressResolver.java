package com.jw.snmpdriver.snmp.address;

import java.util.Optional;

public final class AddressResolver {
    private final ItemPathParser parser;

    public AddressResolver() {
        this(new ItemPathParser());
    }

    public AddressResolver(ItemPathParser parser) {
        this.parser = parser;
    }

    /** Empty unless the identifier is a {@code TRAP.<Field>/<trapOID>} path; invalid TRAP paths throw. */
    public Optional<TrapItemPath> resolveTrap(String identifier) {
        return parser.parseTrap(identifier);
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

