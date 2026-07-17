package com.jw.ignition.license.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jw.ignition.license.exception.LicenseUploadException;
import jakarta.servlet.http.HttpServletRequest;

public final class LicenseMultipartUploadParser {
    public static final int MAX_LICENSE_FILE_BYTES = 1024 * 1024;
    private static final int MAX_MULTIPART_BYTES = MAX_LICENSE_FILE_BYTES + 64 * 1024;
    private static final Pattern NAME_PATTERN = Pattern.compile("name=\"([^\"]+)\"");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("filename=\"([^\"]*)\"");

    // process upload file
    public LicenseUpload parse(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
            throw badRequest("Content-Type multipart/form-data is required.");
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_MULTIPART_BYTES) {
            throw tooLarge();
        }

        String boundary = boundary(contentType);
        byte[] body = readLimited(request);
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Part> fileParts = new ArrayList<>();

        int current = indexOf(body, marker, 0);
        while (current >= 0) {
            int partStart = current + marker.length;
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                break;
            }
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }

            int next = indexOf(body, marker, partStart);
            if (next < 0) {
                break;
            }

            int partEnd = stripTrailingCrlf(body, partStart, next);
            Part part = parsePart(body, partStart, partEnd);
            if (part != null && part.fileName() != null) {
                fileParts.add(part);
            }
            current = next;
        }

        if (fileParts.isEmpty()) {
            throw badRequest("License file is required.");
        }
        if (fileParts.size() > 1) {
            throw badRequest("Only one license file can be uploaded.");
        }

        Part file = fileParts.get(0);
        if (file.content().length == 0) {
            throw badRequest("License file is empty.");
        }
        if (file.content().length > MAX_LICENSE_FILE_BYTES) {
            throw tooLarge();
        }

        return new LicenseUpload(file.fileName(), file.contentType(), file.content());
    }

    private String boundary(String contentType) {
        for (String token : contentType.split(";")) {
            String trimmed = token.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                if (!boundary.isBlank()) {
                    return boundary;
                }
            }
        }
        throw badRequest("Multipart boundary is required.");
    }

    private byte[] readLimited(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        try (var input = request.getInputStream()) {
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_MULTIPART_BYTES) {
                    throw tooLarge();
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private Part parsePart(byte[] body, int start, int end) {
        byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int headerEnd = indexOf(body, separator, start);
        if (headerEnd < 0 || headerEnd > end) {
            return null;
        }

        String headers = new String(body, start, headerEnd - start, StandardCharsets.ISO_8859_1);
        String disposition = header(headers, "content-disposition");
        String name = match(disposition, NAME_PATTERN);
        String fileName = normalizeFileName(match(disposition, FILE_NAME_PATTERN));
        String contentType = header(headers, "content-type");
        int contentStart = headerEnd + separator.length;
        byte[] content = new byte[Math.max(0, end - contentStart)];
        System.arraycopy(body, contentStart, content, 0, content.length);
        return new Part(name, fileName, contentType, content);
    }

    private String header(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String headerName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (name.equals(headerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private String match(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private int stripTrailingCrlf(byte[] body, int start, int end) {
        if (end - start >= 2 && body[end - 2] == '\r' && body[end - 1] == '\n') {
            return end - 2;
        }
        return end;
    }

    private int indexOf(byte[] body, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i <= body.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (body[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private LicenseUploadException badRequest(String message) {
        return new LicenseUploadException(400, "BAD_REQUEST", message);
    }

    private LicenseUploadException tooLarge() {
        return new LicenseUploadException(413, "LICENSE_FILE_TOO_LARGE", "License file must be 1 MB or smaller.");
    }

    private record Part(String name, String fileName, String contentType, byte[] content) {
    }
}
