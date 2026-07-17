package com.jw.ignition.license.model;

import java.security.PublicKey;
import java.time.Clock;

public final class LicenseVerifierConfigModel {
    public int schemaVersion = 1;
    public String moduleId;
    public PublicKey publicKey;
    public double minimumMatchRatio = 0.5d;
    public int minimumLicenseIdentifiers = 1;
    public Clock clock = Clock.systemUTC();
}
