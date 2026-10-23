package com.jw.snmpdriver.snmp.trap;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import com.jw.ignition.license.model.LicenseVerifierConfigModel;
import com.jw.ignition.license.service.MachineIdentifierMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Gateway's local SNMP engine identity for the Trap stack (plan §4.6, ADR 0009): one per registry, persisted in
 * {@code trap-engine.properties}; boots grows by one on every load. A clone or machine change regenerates the
 * engineID; a write failure or exhausted boots only disables v3 Inform.
 */
public final class TrapEngineIdentity {
    public static final String FILE_NAME = "trap-engine.properties";
    static final String ENGINE_ID = "engineId";
    static final String ENGINE_BOOTS = "engineBoots";
    static final String MACHINE_FINGERPRINT = "machineFingerprint";

    private static final Logger logger = LoggerFactory.getLogger(TrapEngineIdentity.class);
    private static final DateTimeFormatter SUFFIX_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();
    /** RFC 3414 §2.2.2: engineID length 5..32 octets. Tests use shorter ids, so only the upper bound is enforced. */
    private static final int MAX_ENGINE_ID_LENGTH = 32;

    /** Writes {@code content} to {@code target} durably; the default is temp file + fsync + ATOMIC_MOVE. */
    @FunctionalInterface
    public interface FileWriter {
        void write(Path target, byte[] content) throws IOException;
    }

    private final byte[] engineId;
    private final int engineBoots;
    private final boolean informAvailable;
    private final String notice;

    private TrapEngineIdentity(byte[] engineId, int engineBoots, boolean informAvailable, String notice) {
        this.engineId = engineId.clone();
        this.engineBoots = engineBoots;
        this.informAvailable = informAvailable;
        this.notice = notice;
    }

    public static TrapEngineIdentity load(Path file, Supplier<Map<String, List<String>>> machine,
                                          Supplier<byte[]> generator, Clock clock) {
        return load(file, machine, generator, clock, TrapEngineIdentity::writeAtomically);
    }

    /** Never throws: every failure still yields an identity, with v3 Inform disabled when it cannot be kept. */
    static TrapEngineIdentity load(Path file, Supplier<Map<String, List<String>>> machine,
                                   Supplier<byte[]> generator, Clock clock, FileWriter writer) {
        Map<String, List<String>> current = machine.get();
        Stored stored;
        try {
            stored = read(file);
        } catch (CorruptException error) {
            String backup = moveAside(file, ".corrupt-", clock);
            logger.warn("Trap engine identity file {} is corrupt ({}); kept as {} and generating a new engineID",
                    file, error.getMessage(), backup);
            return create(file, current, generator, writer, "Trap 本地引擎身份文件损坏（" + error.getMessage()
                    + "），已备份为 " + backup + " 并生成新的 engineID，请在 v3 Inform 发送方重新配置 engineID。");
        } catch (IOException error) {
            logger.warn("Failed to read Trap engine identity file {}; v3 Inform is disabled", file, error);
            return new TrapEngineIdentity(generator.get(), 1, false,
                    "读取 Trap 本地引擎身份文件失败（" + error + "），v3 Inform 已停用。");
        }
        if (stored == null) {
            return create(file, current, generator, writer, null);
        }

        Map<String, List<String>> fingerprint = stored.fingerprint;
        if (current.isEmpty()) {
            logger.warn("No machine identifier available; keeping Trap engineID {} without clone detection",
                    HEX.formatHex(stored.engineId));
        } else {
            if (!fingerprint.isEmpty() && !MachineIdentifierMatcher.matches(fingerprint, current, matchRatio())) {
                String backup = moveAside(file, ".cloned-", clock);
                logger.warn("Machine fingerprint differs from Trap engine identity {}; kept as {} and generating"
                        + " a new engineID (Gateway cloned or moved)", file, backup);
                return create(file, current, generator, writer, "检测到 Gateway 被克隆或更换机器，已生成新的 Trap"
                        + " 本地 engineID，旧文件备份为 " + backup + "；请在 v3 Inform 发送方重新配置 engineID。"
                        + "若为误判，可从备份恢复文件后重启模块。");
            }
            fingerprint = current;
        }

        if (stored.engineBoots >= Integer.MAX_VALUE - 1) {
            TrapEngineIdentity identity = save(file, stored.engineId, Integer.MAX_VALUE, fingerprint, writer, null);
            logger.warn("Trap engineBoots reached {}; v3 Inform is disabled", Integer.MAX_VALUE);
            return identity.informAvailable ? new TrapEngineIdentity(identity.engineId, Integer.MAX_VALUE, false,
                    "Trap 本地 engineBoots 已达到上限 2147483647（RFC 3414），v3 Inform 已停用；"
                            + "删除身份文件后重启模块可生成新的 engineID。") : identity;
        }
        return save(file, stored.engineId, stored.engineBoots + 1, fingerprint, writer, null);
    }

    /** An identity that could not be persisted at all (no config directory); v3 Inform stays disabled. */
    public static TrapEngineIdentity unpersisted(byte[] engineId, String notice) {
        return new TrapEngineIdentity(engineId, 1, false, notice);
    }

    public byte[] engineId() {
        return engineId.clone();
    }

    /** Lower-case hex without separators, as shown in {@code Traps/LocalEngineId}. */
    public String engineIdHex() {
        return HEX.formatHex(engineId);
    }

    public int engineBoots() {
        return engineBoots;
    }

    /** False when the identity could not be persisted or boots are exhausted; v1/v2c and v3 Trap are unaffected. */
    public boolean informAvailable() {
        return informAvailable;
    }

    /** Operator-facing text for {@code Traps/LastError}, or null when nothing needs attention. */
    public String notice() {
        return notice;
    }

    private static TrapEngineIdentity create(Path file, Map<String, List<String>> fingerprint,
                                             Supplier<byte[]> generator, FileWriter writer, String notice) {
        return save(file, generator.get(), 1, fingerprint, writer, notice);
    }

    private static TrapEngineIdentity save(Path file, byte[] engineId, int boots, Map<String, List<String>> fingerprint,
                                           FileWriter writer, String notice) {
        String content = ENGINE_ID + "=" + HEX.formatHex(engineId) + "\n"
                + ENGINE_BOOTS + "=" + boots + "\n"
                + MACHINE_FINGERPRINT + "=" + formatFingerprint(fingerprint) + "\n";
        try {
            writer.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
            return new TrapEngineIdentity(engineId, boots, true, notice);
        } catch (IOException | RuntimeException error) {
            logger.warn("Failed to write Trap engine identity file {}; v3 Inform is disabled", file, error);
            String failure = "写入 Trap 本地引擎身份文件失败（" + error + "），v3 Inform 已停用；v1/v2c 接收不受影响。";
            return new TrapEngineIdentity(engineId, boots, false, notice == null ? failure : notice + " " + failure);
        }
    }

    /** Temp file in the same directory, fsync, then ATOMIC_MOVE over the target. Creates the directory. */
    public static void writeAtomically(Path target, byte[] content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Stored read(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException missing) {
            return null;
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IllegalArgumentException error) {
            throw new CorruptException("无法解析");
        }
        byte[] engineId;
        try {
            engineId = HEX.parseHex(properties.getProperty(ENGINE_ID, "").trim());
        } catch (IllegalArgumentException error) {
            throw new CorruptException("engineId 不是十六进制");
        }
        if (engineId.length == 0 || engineId.length > MAX_ENGINE_ID_LENGTH) {
            throw new CorruptException("engineId 长度无效");
        }
        int boots;
        try {
            boots = Integer.parseInt(properties.getProperty(ENGINE_BOOTS, "").trim());
        } catch (NumberFormatException error) {
            throw new CorruptException("engineBoots 不是整数");
        }
        if (boots < 1) {
            throw new CorruptException("engineBoots 小于 1");
        }
        return new Stored(engineId, boots, parseFingerprint(properties.getProperty(MACHINE_FINGERPRINT, "")));
    }

    /** {@code key=hash} pairs, comma separated; a key may repeat (MAC addresses). */
    static String formatFingerprint(Map<String, List<String>> fingerprint) {
        List<String> entries = new ArrayList<>();
        fingerprint.forEach((key, hashes) -> hashes.forEach(hash -> entries.add(key + "=" + hash)));
        return String.join(",", entries);
    }

    static Map<String, List<String>> parseFingerprint(String text) {
        Map<String, List<String>> fingerprint = new LinkedHashMap<>();
        for (String entry : text.split(",")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = entry.substring(0, equals).trim();
            String hash = entry.substring(equals + 1).trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !hash.isEmpty()) {
                fingerprint.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hash);
            }
        }
        return fingerprint;
    }

    private static String moveAside(Path file, String suffix, Clock clock) {
        Path backup = file.resolveSibling(file.getFileName() + suffix + SUFFIX_TIME.format(clock.instant()));
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            logger.warn("Failed to keep Trap engine identity file {} as {}", file, backup, error);
        }
        return backup.getFileName().toString();
    }

    private static double matchRatio() {
        return new LicenseVerifierConfigModel().minimumMatchRatio;
    }

    private record Stored(byte[] engineId, int engineBoots, Map<String, List<String>> fingerprint) {
    }

    private static final class CorruptException extends IOException {
        CorruptException(String message) {
            super(message);
        }
    }
}
