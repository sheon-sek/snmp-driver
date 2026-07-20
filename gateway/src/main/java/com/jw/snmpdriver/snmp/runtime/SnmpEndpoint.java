package com.jw.snmpdriver.snmp.runtime;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;

public record SnmpEndpoint(
        String deviceId,
        String host,
        int port,
        SNMPDeviceConfig.IpFamily ipFamily,
        SNMPDeviceConfig.Version version,
        String readCommunity,
        String writeCommunity,
        String securityName,
        SNMPDeviceConfig.SecurityLevel securityLevel,
        SNMPDeviceConfig.AuthProtocol authProtocol,
        String authPassphrase,
        SNMPDeviceConfig.PrivacyProtocol privacyProtocol,
        String privacyPassphrase,
        String contextName,
        SNMPDeviceConfig.AccessMode accessMode,
        long timeoutMillis,
        int retries,
        int maxOidsPerRequest,
        int maxRequestBytes,
        int maxInflightPerDevice
) {
    @Override
    public String toString() {
        return "SnmpEndpoint[deviceId=" + deviceId
                + ", host=" + host
                + ", port=" + port
                + ", ipFamily=" + ipFamily
                + ", version=" + version
                + ", securityLevel=" + securityLevel
                + ", accessMode=" + accessMode
                + ", timeoutMillis=" + timeoutMillis
                + ", retries=" + retries
                + ", maxOidsPerRequest=" + maxOidsPerRequest
                + ", maxRequestBytes=" + maxRequestBytes
                + ", maxInflightPerDevice=" + maxInflightPerDevice
                + "]";
    }
}

