package com.jw.snmpdriver.snmp.address;

import java.util.Arrays;
import java.util.List;

public record NumericOid(String canonical, List<Long> arcs) {
    public NumericOid {
        arcs = List.copyOf(arcs);
    }

    public static NumericOid parse(String input) {
        if (input == null
                || input.isBlank()
                || !input.equals(input.trim())
                || input.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("OID must be non-blank and contain no spaces");
        }

        String value = input.startsWith(".") ? input.substring(1) : input;
        if (!value.matches("[0-9]+(?:\\.[0-9]+)+")) {
            throw new IllegalArgumentException("OID must use numeric dotted notation");
        }

        List<Long> arcs;
        try {
            arcs = Arrays.stream(value.split("\\."))
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("OID arc is outside ASN.1 bounds", error);
        }

        if (arcs.get(0) > 2
                || (arcs.get(0) < 2 && arcs.get(1) > 39)
                || arcs.stream().anyMatch(arc -> arc < 0 || arc > 0xffff_ffffL)) {
            throw new IllegalArgumentException("OID arc is outside ASN.1 bounds");
        }

        String canonical = String.join(".", arcs.stream().map(String::valueOf).toList());
        return new NumericOid(canonical, arcs);
    }
}

