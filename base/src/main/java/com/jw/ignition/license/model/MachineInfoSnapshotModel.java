package com.jw.ignition.license.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MachineInfoSnapshotModel {
    public Map<String, String> rawIdentifiers = new LinkedHashMap<>();
    public Map<String, String> normalizedIdentifiers = new LinkedHashMap<>();
    public Map<String, String> hashedIdentifiers = new LinkedHashMap<>();
    public MachineInfoModel rawMachineInfo = new MachineInfoModel();
    public MachineInfoModel normalizedMachineInfo = new MachineInfoModel();
    public MachineInfoModel hashedMachineInfo = new MachineInfoModel();
    public int capturedIdentifiers;
    public List<String> warnings = new ArrayList<>();
}
