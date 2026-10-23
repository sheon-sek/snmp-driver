package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrapEngineIdentityTest {
    private static final Map<String, List<String>> MACHINE = Map.of(
            "systemUuid", List.of("sha256:u1"),
            "diskSerialNumber", List.of("sha256:d1"),
            "macAddress", List.of("sha256:m1", "sha256:m2"));
    private static final Map<String, List<String>> OTHER_MACHINE = Map.of(
            "systemUuid", List.of("sha256:u9"),
            "diskSerialNumber", List.of("sha256:d9"),
            "macAddress", List.of("sha256:m1"));

    @TempDir
    Path dir;

    private final AtomicInteger generated = new AtomicInteger();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T01:02:03Z"), ZoneOffset.UTC);

    private Path file() {
        return dir.resolve("local").resolve("trap-engine.properties");
    }

    private TrapEngineIdentity load(Map<String, List<String>> machine) {
        return load(machine, TrapEngineIdentity::writeAtomically);
    }

    private TrapEngineIdentity load(Map<String, List<String>> machine, TrapEngineIdentity.FileWriter writer) {
        return TrapEngineIdentity.load(file(), () -> machine,
                () -> new byte[] {(byte) 0x80, 0, 0x1F, (byte) generated.incrementAndGet()}, clock, writer);
    }

    private Properties stored() throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(file(), StandardCharsets.ISO_8859_1)));
        return properties;
    }

    private List<String> siblings() throws IOException {
        try (Stream<Path> files = Files.list(file().getParent())) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void firstStartGeneratesEngineIdWithBootsOne() throws IOException {
        TrapEngineIdentity identity = load(MACHINE);

        assertArrayEquals(new byte[] {(byte) 0x80, 0, 0x1F, 1}, identity.engineId());
        assertEquals("80001f01", identity.engineIdHex());
        assertEquals(1, identity.engineBoots());
        assertTrue(identity.informAvailable());
        assertNull(identity.notice());
        Properties stored = stored();
        assertEquals("80001f01", stored.getProperty("engineId"));
        assertEquals("1", stored.getProperty("engineBoots"));
        assertTrue(stored.getProperty("machineFingerprint").contains("macAddress=sha256:m2"));
    }

    @Test
    void restartKeepsEngineIdAndIncrementsBoots() throws IOException {
        load(MACHINE);
        TrapEngineIdentity identity = load(MACHINE);

        assertEquals("80001f01", identity.engineIdHex());
        assertEquals(2, identity.engineBoots());
        assertEquals("2", stored().getProperty("engineBoots"));
        assertEquals(List.of("trap-engine.properties"), siblings());
    }

    @Test
    void partialMachineChangeAboveTheLicenseRatioIsTheSameMachine() {
        load(MACHINE);
        TrapEngineIdentity identity = load(Map.of(
                "systemUuid", List.of("sha256:u1"),
                "diskSerialNumber", List.of("sha256:d9"),
                "macAddress", List.of("sha256:m2")));

        assertEquals("80001f01", identity.engineIdHex());
        assertEquals(2, identity.engineBoots());
    }

    @Test
    void mismatchedFingerprintRegeneratesAndKeepsOldFileAsCloned() throws IOException {
        load(MACHINE);
        load(MACHINE);
        TrapEngineIdentity identity = load(OTHER_MACHINE);

        assertEquals("80001f02", identity.engineIdHex());
        assertEquals(1, identity.engineBoots());
        assertTrue(identity.informAvailable());
        assertTrue(identity.notice().contains("克隆"), identity.notice());
        assertEquals(List.of("trap-engine.properties", "trap-engine.properties.cloned-20261023T010203Z"),
                siblings());
        Properties old = new Properties();
        old.load(new StringReader(Files.readString(
                file().resolveSibling("trap-engine.properties.cloned-20261023T010203Z"))));
        assertEquals("2", old.getProperty("engineBoots"));
        assertTrue(stored().getProperty("machineFingerprint").contains("sha256:u9"));
    }

    @Test
    void noMachineIdentifiersKeepsEngineIdAndFingerprintWithoutNotice() throws IOException {
        load(MACHINE);
        String fingerprint = stored().getProperty("machineFingerprint");
        TrapEngineIdentity identity = load(Map.of());

        assertEquals("80001f01", identity.engineIdHex());
        assertEquals(2, identity.engineBoots());
        assertNull(identity.notice());
        assertEquals(fingerprint, stored().getProperty("machineFingerprint"));
    }

    @Test
    void corruptFileIsRenamedAndEngineIdRegenerated() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "engineId=zz\nengineBoots=abc\n");

        TrapEngineIdentity identity = load(MACHINE);

        assertEquals("80001f01", identity.engineIdHex());
        assertEquals(1, identity.engineBoots());
        assertTrue(identity.informAvailable());
        assertTrue(identity.notice().contains("损坏"), identity.notice());
        assertEquals(List.of("trap-engine.properties", "trap-engine.properties.corrupt-20261023T010203Z"),
                siblings());
    }

    @Test
    void parentDirectoryIsSyncedAfterTheMoveLandsTheFile() {
        List<Path> synced = new java.util.ArrayList<>();
        TrapEngineIdentity identity = load(MACHINE, (target, content) -> TrapEngineIdentity.writeAtomically(
                target, content, directory -> {
                    assertTrue(Files.exists(target), "fsync 目录须在 ATOMIC_MOVE 之后");
                    synced.add(directory);
                }));
        assertTrue(identity.informAvailable());
        assertEquals(List.of(file().getParent().toAbsolutePath()), synced);
    }

    @Test
    void directorySyncIoFailureIsAWriteFailure() {
        TrapEngineIdentity identity = load(MACHINE, (target, content) -> TrapEngineIdentity.writeAtomically(
                target, content, directory -> {
                    throw new IOException("EIO");
                }));
        assertFalse(identity.informAvailable());
        assertTrue(identity.notice().contains("EIO"), identity.notice());
    }

    @Test
    void directorySyncUnsupportedByThePlatformCountsAsSuccess() {
        TrapEngineIdentity identity = load(MACHINE, (target, content) -> TrapEngineIdentity.writeAtomically(
                target, content, directory -> {
                    throw new UnsupportedOperationException("no directory fsync");
                }));
        assertTrue(identity.informAvailable());
        assertNull(identity.notice());
    }

    @Test
    void writeFailureDisablesInformButKeepsAnIdentity() {
        TrapEngineIdentity identity = load(MACHINE, (target, content) -> {
            throw new IOException("disk full");
        });

        assertEquals("80001f01", identity.engineIdHex());
        assertFalse(identity.informAvailable());
        assertTrue(identity.notice().contains("disk full"), identity.notice());
        assertFalse(Files.exists(file()));
    }

    @Test
    void bootsReachingTheMaximumDisablesInform() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "engineId=80001f07\nengineBoots=2147483646\nmachineFingerprint=\n");

        TrapEngineIdentity reached = load(MACHINE);
        assertEquals(Integer.MAX_VALUE, reached.engineBoots());
        assertFalse(reached.informAvailable());
        assertTrue(reached.notice().contains("2147483647"), reached.notice());

        TrapEngineIdentity stays = load(MACHINE);
        assertEquals("80001f07", stays.engineIdHex());
        assertEquals(Integer.MAX_VALUE, stays.engineBoots());
        assertFalse(stays.informAvailable());
    }

    @Test
    void missingFingerprintInFileIsNotTreatedAsClone() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "engineId=80001f07\nengineBoots=5\n");

        TrapEngineIdentity identity = load(MACHINE);

        assertEquals("80001f07", identity.engineIdHex());
        assertEquals(6, identity.engineBoots());
        assertNotEquals("", stored().getProperty("machineFingerprint"));
    }

    @Test
    void writeLeavesNoTemporaryFile() throws IOException {
        load(MACHINE);
        load(MACHINE);

        assertEquals(List.of("trap-engine.properties"), siblings());
    }
}
