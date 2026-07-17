package com.jw.ignition.license.model;

import java.util.ArrayList;
import java.util.List;

public final class LicensePayloadModel {
    public String licenseId;
    public String client;
    public LicenseModuleModel module;
    public String issuedAt;
    public String expiresAt;
    public int capturedIdentifiers;
    public MachineInfoModel machineInfo;
    public List<String> features = new ArrayList<>();
}
