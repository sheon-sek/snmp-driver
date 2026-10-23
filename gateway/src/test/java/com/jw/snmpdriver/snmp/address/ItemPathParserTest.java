package com.jw.snmpdriver.snmp.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.junit.jupiter.api.Test;

class ItemPathParserTest {
    private final ItemPathParser parser = new ItemPathParser();

    @Test
    void trapPathsParseToTheirFieldAndTrapOid() {
        TrapItemPath count = parser.parseTrap("TRAP.Count/1.3.6.1.6.3.1.1.5.3").orElseThrow();
        assertEquals(TrapItemPath.Field.COUNT, count.field());
        assertEquals("1.3.6.1.6.3.1.1.5.3", count.trapOid().canonical());
        assertEquals("TRAP.Count/1.3.6.1.6.3.1.1.5.3", count.canonical());

        assertEquals(TrapItemPath.Field.LAST_TIME, parser.parseTrap("TRAP.LastTime/1.3.6.1.4.1.9.9.1").orElseThrow().field());
        assertEquals(TrapItemPath.Field.LAST_VARBINDS,
                parser.parseTrap("TRAP.LastVarbinds/1.3.6.1.4.1.9.9.1").orElseThrow().field());
        assertEquals(TrapItemPath.Field.SEQUENCE, parser.parseTrap("TRAP.Sequence/1.3.6.1.4.1.9.9.1").orElseThrow().field());
    }

    @Test
    void trapPrefixAndFieldAreCaseInsensitiveAndLeadingDotOidIsCanonicalised() {
        TrapItemPath path = parser.parseTrap("trap.count/.1.3.6.1.6.3.1.1.5.3").orElseThrow();

        assertEquals(TrapItemPath.Field.COUNT, path.field());
        assertEquals("TRAP.Count/1.3.6.1.6.3.1.1.5.3", path.canonical());
    }

    @Test
    void trapFieldsDeclareTheirOpcUaTypes() {
        assertEquals(Identifiers.UInt64, TrapItemPath.Field.COUNT.dataType());
        assertEquals(Identifiers.UInt64, TrapItemPath.Field.SEQUENCE.dataType());
        assertEquals(Identifiers.DateTime, TrapItemPath.Field.LAST_TIME.dataType());
        assertEquals(Identifiers.String, TrapItemPath.Field.LAST_VARBINDS.dataType());
    }

    @Test
    void nonTrapPathsAreNotTrapPaths() {
        assertEquals(Optional.empty(), parser.parseTrap("1.3.6.1.2.1.1.5.0"));
        assertEquals(Optional.empty(), parser.parseTrap("RO.Integer32/1.3.6.1.2.1.1.5.0"));
        assertEquals(Optional.empty(), parser.parseTrap("RW.Integer32/1.3.6.1.2.1.1.5.0"));
    }

    @Test
    void invalidTrapPathsGiveClearErrors() {
        for (String bad : new String[] {
                "TRAP.Count/", "TRAP.Count", "TRAP/1.3.6.1.6.3.1.1.5.3", "TRAP.Bogus/1.3.6.1.6.3.1.1.5.3",
                "TRAP.Count/not-an-oid", "TRAP.Count/1.3.6.1.6.3.1.1.5.3/extra", "TRAP./1.3.6.1.6.3.1.1.5.3",
                "TRAP.Count.Extra/1.3.6.1.6.3.1.1.5.3", "TRAP.Count/1", "TRAP.Count/3.1.2"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parser.parseTrap(bad), bad);
            assertTrue(error.getMessage().length() > 10, bad);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parser.parseTrap("TRAP.Bogus/1.3.6.1.6.3.1.1.5.3"))
                .getMessage().contains("Count"));
    }

    @Test
    void existingRoRwSyntaxIsUnchanged() {
        ParsedItemPath bare = parser.parse("1.3.6.1.2.1.1.5.0");
        assertEquals(ParsedItemPath.Access.READ_ONLY, bare.access());
        assertTrue(bare.stringFallback());

        ParsedItemPath rw = parser.parse("RW.Integer32/.1.3.6.1.2.1.1.7.0");
        assertEquals(ParsedItemPath.Access.READ_WRITE, rw.access());
        assertEquals("RW.Integer32/1.3.6.1.2.1.1.7.0", rw.canonical());

        assertEquals("RO.Counter64/1.3.6.1.2.1.31.1.1.1.6.1", parser.parse("Counter64/1.3.6.1.2.1.31.1.1.1.6.1").canonical());
        assertThrows(IllegalArgumentException.class, () -> parser.parse("XX.Integer32/1.3.6.1.2.1.1.5.0"));
    }

    @Test
    void parseRejectsTrapPathsWithAnExplanationInsteadOfTreatingThemAsSnmpItems() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("TRAP.Count/1.3.6.1.6.3.1.1.5.3"));
        assertTrue(error.getMessage().contains("TRAP"), error.getMessage());
    }
}
