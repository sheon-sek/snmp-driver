package com.jw.ignition.license.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MachineInfoRequestModel {
    public int capturedIdentifiers;
    public MachineInfoModel machineInfo = new MachineInfoModel();
    public MachineInfoModel hashedMachineInfo = new MachineInfoModel();
    public Map<String, String> rawIdentifiers = new LinkedHashMap<>();
    public Map<String, String> hashedIdentifiers = new LinkedHashMap<>();
    public List<String> warnings = new ArrayList<>();
}
