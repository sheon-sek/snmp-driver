package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SecurityNameConflictTest {
    private static final String ENGINE = "8000000001020304";
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final TrapListenerRegistry registry = new TrapListenerRegistry((address, port) -> () -> { }, scheduler);

    @AfterEach
    void tearDown() {
        registry.closeAll();
        scheduler.shutdownNow();
    }

    private static TrapV3User user(String name, String auth, String ref) {
        return TrapV3User.of(name, SNMPDeviceConfig.SecurityLevel.AUTH_PRIV, SNMPDeviceConfig.AuthProtocol.SHA256,
                auth, SNMPDeviceConfig.PrivacyProtocol.AES128, "privpass1", ENGINE, ref);
    }

    private TrapListenerHandle acquire(String device, int port, TrapV3User user, Runnable onChange) {
        TrapRecipient recipient = new TrapRecipient(device,
                new TrapFilter("127.0.0.1", "", new byte[0], host -> new java.net.InetAddress[0]),
                new TrapCounters(), new TokenBucket(100), notification -> { }, notice -> { }, true, user);
        return registry.acquire(device, "127.0.0.1", port, onChange, recipient);
    }

    @Test
    void differentPassphrasesDegradeBothDevicesNamingTheOther() {
        TrapListenerHandle a = acquire("dev-a", 10162, user("trapuser", "authpass1", "p/s"), null);
        TrapListenerHandle b = acquire("dev-b", 10162, user("trapuser", "authpass2", "p/s"), null);

        assertNotNull(a.conflict());
        assertNotNull(b.conflict());
        assertTrue(a.conflict().contains("dev-b"));
        assertFalse(a.conflict().contains("dev-a"));
        assertTrue(b.conflict().contains("dev-a"));
        // 冲突用户不注册，谁先启动都不生效
        assertEquals(0, registry.usmStackOf(10162).userCount());
    }

    @Test
    void differentSecretReferenceConflictsEvenWithSamePlaintext() {
        TrapListenerHandle a = acquire("dev-a", 10162, user("trapuser", "authpass1", "p/s1"), null);
        acquire("dev-b", 10162, user("trapuser", "authpass1", "p/s2"), null);

        assertNotNull(a.conflict());
    }

    @Test
    void identicalConfigurationIsNoConflict() {
        TrapListenerHandle a = acquire("dev-a", 10162, user("trapuser", "authpass1", "p/s"), null);
        TrapListenerHandle b = acquire("dev-b", 10162, user("trapuser", "authpass1", "p/s"), null);

        assertNull(a.conflict());
        assertNull(b.conflict());
        assertEquals(1, registry.usmStackOf(10162).userCount());
    }

    @Test
    void resultDoesNotDependOnStartOrder() {
        TrapListenerHandle b = acquire("dev-b", 10162, user("trapuser", "authpass2", "p/s"), null);
        TrapListenerHandle c = acquire("dev-c", 10162, user("trapuser", "authpass1", "p/s"), null);
        TrapListenerHandle a = acquire("dev-a", 10162, user("trapuser", "authpass1", "p/s"), null);

        TrapListenerRegistry other = new TrapListenerRegistry((address, port) -> () -> { }, scheduler);
        TrapRecipientFactory factory = new TrapRecipientFactory();
        TrapListenerHandle a2 = other.acquire("dev-a", "127.0.0.1", 10162, null,
                factory.of("dev-a", user("trapuser", "authpass1", "p/s")));
        TrapListenerHandle c2 = other.acquire("dev-c", "127.0.0.1", 10162, null,
                factory.of("dev-c", user("trapuser", "authpass1", "p/s")));
        TrapListenerHandle b2 = other.acquire("dev-b", "127.0.0.1", 10162, null,
                factory.of("dev-b", user("trapuser", "authpass2", "p/s")));

        assertNotNull(a.conflict());
        assertEquals(a.conflict(), a2.conflict());
        assertEquals(b.conflict(), b2.conflict());
        assertEquals(c.conflict(), c2.conflict());
        assertTrue(a.conflict().contains("dev-b") && a.conflict().contains("dev-c"));
        other.closeAll();
    }

    @Test
    void releasingOneRecoversTheOtherAndNotifiesIt() {
        AtomicInteger changes = new AtomicInteger();
        TrapListenerHandle a = acquire("dev-a", 10162, user("trapuser", "authpass1", "p/s"),
                changes::incrementAndGet);
        TrapListenerHandle b = acquire("dev-b", 10162, user("trapuser", "authpass2", "p/s"), null);
        assertEquals(1, changes.get());

        b.release();

        assertNull(a.conflict());
        assertEquals(2, changes.get());
        assertEquals(1, registry.usmStackOf(10162).userCount());
    }

    @Test
    void differentListenersDoNotInterfere() {
        TrapListenerHandle a = acquire("dev-a", 10162, user("trapuser", "authpass1", "p/s"), null);
        TrapListenerHandle b = acquire("dev-b", 10163, user("trapuser", "authpass2", "p/s"), null);

        assertNull(a.conflict());
        assertNull(b.conflict());
    }

    @Test
    void differentSecurityNamesDoNotConflict() {
        TrapListenerHandle a = acquire("dev-a", 10162, user("user1", "authpass1", "p/s"), null);
        TrapListenerHandle b = acquire("dev-b", 10162, user("user2", "authpass2", "p/s2"), null);

        assertNull(a.conflict());
        assertNull(b.conflict());
        assertEquals(2, registry.usmStackOf(10162).userCount());
    }

    private static final class TrapRecipientFactory {
        TrapRecipient of(String device, TrapV3User user) {
            return new TrapRecipient(device,
                    new TrapFilter("127.0.0.1", "", new byte[0], host -> new java.net.InetAddress[0]),
                    new TrapCounters(), new TokenBucket(100), notification -> { }, notice -> { }, true, user);
        }
    }
}
