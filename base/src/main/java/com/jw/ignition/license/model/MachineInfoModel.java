package com.jw.ignition.license.model;

import java.util.ArrayList;
import java.util.List;

public final class MachineInfoModel {
    public String machineGuid;
    public String systemUuid;
    public String biosSerialNumber;
    public String motherboardSerialNumber;
    public String diskSerialNumber;
    public String cpuProcessorId;
    public List<String> macAddress = new ArrayList<>();
}
