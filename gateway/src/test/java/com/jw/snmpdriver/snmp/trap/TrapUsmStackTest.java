package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HexFormat;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import org.junit.jupiter.api.Test;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;

/** The Trap USM never shares state with the polling stack, the SNMP4J globals or another listener. */
class TrapUsmStackTest {
    private static final String ENGINE_HEX = "80001f8880aabbccdd01";

    private static TrapV3User user(String engineHex) {
        return TrapV3User.of("trapuser", SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256,
                "authpass123", SNMPDeviceConfig.PrivacyProtocol.AES128, "privpass123", engineHex);
    }

    @Test
    void ownsItsProtocolsUsmAndMpv3() {
        TrapUsmStack stack = new TrapUsmStack(MPv3.createLocalEngineID(), 1);
        assertNotSame(SecurityProtocols.getInstance(), stack.securityProtocols());
        SecurityModel global = SecurityModels.getInstance().getSecurityModel(new Integer32(3));
        assertNotSame(global, stack.usm());
        assertNotSame(stack.usm(), new TrapUsmStack(MPv3.createLocalEngineID(), 1).usm());
        assertFalse(stack.usm().isEngineDiscoveryEnabled());
    }

    @Test
    void usersAreLocalizedToTheSenderAndNeverReachOtherStacks() {
        TrapUsmStack stack = new TrapUsmStack(MPv3.createLocalEngineID(), 1);
        TrapUsmStack other = new TrapUsmStack(MPv3.createLocalEngineID(), 1);
        stack.register(user(ENGINE_HEX));
        OctetString engine = new OctetString(HexFormat.of().parseHex(ENGINE_HEX));
        OctetString name = new OctetString("trapuser");
        UsmUser stored = stack.usm().getUserTable().getUser(engine, name).getUsmUser();
        assertEquals(UsmUser.LocalizationGrant.never, stored.getLocalizationGrant());
        assertNull(stack.usm().getUserTable().getUser(name), "no wildcard user localizable on the fly");
        assertEquals(0, other.userCount());
        assertNull(SecurityModels.getInstance().getSecurityModel(new Integer32(3)) instanceof org.snmp4j.security.USM usm
                ? usm.getUserTable().getUser(engine, name) : null);
    }

    @Test
    void sharedUserIsRemovedOnlyWithTheLastRegistration() {
        TrapUsmStack stack = new TrapUsmStack(MPv3.createLocalEngineID(), 1);
        stack.register(user(ENGINE_HEX));
        stack.register(user(ENGINE_HEX));
        stack.unregister(user(ENGINE_HEX));
        assertEquals(1, stack.userCount());
        stack.unregister(user(ENGINE_HEX));
        assertEquals(0, stack.userCount());
        assertFalse(stack.tracksEngine(HexFormat.of().parseHex(ENGINE_HEX)));
    }

    @Test
    void userWithoutSenderEngineIdIsNotRegistered() {
        TrapUsmStack stack = new TrapUsmStack(MPv3.createLocalEngineID(), 1);
        stack.register(user(""));
        assertEquals(0, stack.userCount());
    }
}
