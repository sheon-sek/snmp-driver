package com.jw.ignition.license.upload;

public record LicenseUpload(String fileName, String contentType, byte[] content) {
}
