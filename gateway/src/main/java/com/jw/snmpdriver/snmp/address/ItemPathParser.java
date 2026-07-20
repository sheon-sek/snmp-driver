package com.jw.snmpdriver.snmp.address;

import java.util.Optional;

public final class ItemPathParser {
    public ParsedItemPath parse(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(
                    "SNMP Item Path is blank; expected <OID>, <Type>/<OID>, RO.<Type>/<OID>, or RW.<Type>/<OID>"
            );
        }
        String value = identifier.trim();
        if (value.contains("?") || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("SNMP Item Path must not contain internal whitespace or query syntax");
        }

        int slash = value.indexOf('/');
        if (slash < 0) {
            return new ParsedItemPath(
                    ParsedItemPath.Access.READ_ONLY,
                    Optional.empty(),
                    NumericOid.parse(value)
            );
        }
        if (slash == 0 || slash == value.length() - 1 || slash != value.lastIndexOf('/')) {
            throw new IllegalArgumentException(
                    "Invalid typed SNMP Item Path; expected <Type>/<OID>, RO.<Type>/<OID>, or RW.<Type>/<OID>"
            );
        }

        String qualifier = value.substring(0, slash);
        ParsedItemPath.Access access = ParsedItemPath.Access.READ_ONLY;
        String typeToken = qualifier;
        int dot = qualifier.indexOf('.');
        if (dot >= 0) {
            if (dot == 0 || dot == qualifier.length() - 1 || dot != qualifier.lastIndexOf('.')) {
                throw new IllegalArgumentException(
                        "Invalid SNMP access/type qualifier; expected RO.<Type> or RW.<Type>"
                );
            }
            String accessToken = qualifier.substring(0, dot);
            if (accessToken.equalsIgnoreCase("RW")) {
                access = ParsedItemPath.Access.READ_WRITE;
            } else if (!accessToken.equalsIgnoreCase("RO")) {
                throw new IllegalArgumentException(
                        "Unsupported SNMP access qualifier '" + accessToken + "'; expected RO or RW"
                );
            }
            typeToken = qualifier.substring(dot + 1);
        } else if (qualifier.equalsIgnoreCase("RW")) {
            throw new IllegalArgumentException(
                    "ReadWrite Item Path requires RW.<Type>/<OID>; RW/<OID> is not supported"
            );
        } else if (qualifier.equalsIgnoreCase("RO")) {
            throw new IllegalArgumentException(
                    "ReadOnly typed Item Path requires RO.<Type>/<OID>; RO/<OID> is not supported"
            );
        }

        if (typeToken.equalsIgnoreCase("String")) {
            throw new IllegalArgumentException(
                    "Explicit String/<OID> is not supported; use a bare <OID> for read-only String fallback"
            );
        }
        return new ParsedItemPath(
                access,
                Optional.of(SnmpWireType.fromToken(typeToken)),
                NumericOid.parse(value.substring(slash + 1))
        );
    }
}

