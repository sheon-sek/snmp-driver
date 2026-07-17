package com.jw.ignition.license.model;

import com.jw.ignition.license.enums.LicenseStatusCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LicenseStatusModel {
    public boolean licensed;
    public LicenseStatusCode code;
    public String message;
    public String verifiedAt;
    public String licensePath;
    public int schemaVersion;
    public String keyId;
    public String algorithm;
    public String licenseId;
    public String client;
    public String moduleId;
    public String issuedAt;
    public String expiresAt;
    public int capturedIdentifiers;
    public int licenseIdentifierCount;
    public int currentIdentifierCount;
    public int matchedIdentifiers;
    public int requiredMatchedIdentifiers;
    public double matchRatio;
    public List<String> features = new ArrayList<>();
    public Map<String, IdentifierMatchModel> identifierMatches = new LinkedHashMap<>();
    public List<String> warnings = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public static LicenseStatusModel empty() {
        LicenseStatusModel status = new LicenseStatusModel();
        status.licensed = false;
        return status;
    }
}
