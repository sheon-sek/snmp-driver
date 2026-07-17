package com.jw.ignition.license.service;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class LicenseFileStoreService {
    private static final String LICENSE_DIRECTORY = "license";
    private static final String LICENSE_FILE_NAME = "license.json";

    private final Path licensePath;

    public LicenseFileStoreService(GatewayContext context, String mountAlias) {
        this(context.getSystemManager().getDataDir().toPath(), mountAlias);
    }

    // resolve Ignition data installation module directory
    public LicenseFileStoreService(Path dataDirectory, String mountAlias) {
        if (dataDirectory == null) {
            throw new IllegalArgumentException("Ignition data directory is required.");
        }
        Path dataRoot = dataDirectory.toAbsolutePath().normalize();
        this.licensePath = dataRoot
            .resolve(safePathSegment(mountAlias))
            .resolve(LICENSE_DIRECTORY)
            .resolve(LICENSE_FILE_NAME)
            .normalize();
        if (!licensePath.startsWith(dataRoot)) {
            throw new IllegalArgumentException("License path must be inside the Ignition data directory.");
        }
    }

    public Path licensePath() {
        return licensePath;
    }

    public boolean exists() {
        return Files.isRegularFile(licensePath);
    }

    // read license file from Ignition data installation module directory
    public String read() {
        try {
            return Files.readString(licensePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read module license file.", e);
        }
    }

    public byte[] readBytes() {
        try {
            return Files.readAllBytes(licensePath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read module license file.", e);
        }
    }

    // create license file at Ignition data installation module directory
    public void write(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("License content is required.");
        }

        Path directory = licensePath.getParent();
        Path partialPath = directory.resolve(LICENSE_FILE_NAME + ".part").normalize();
        if (!partialPath.startsWith(directory)) {
            throw new IllegalStateException("License temporary path must be inside the license directory.");
        }

        try {
            Files.createDirectories(directory);
            Files.write(partialPath, content);
            try {
                Files.move(partialPath, licensePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partialPath, licensePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write module license file.", e);
        }
    }

    private String safePathSegment(String value) {
        String segment = value == null ? "" : value.trim();
        if (segment.isBlank()) {
            throw new IllegalArgumentException("Module mount alias is required.");
        }
        if (segment.contains("/") || segment.contains("\\") || ".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("Module mount alias must be a single path segment.");
        }
        return segment;
    }
}
