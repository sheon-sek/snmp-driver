package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TrapFilterTest {
    private static final TrapFilter.HostLookup NO_DNS = host -> {
        throw new UnknownHostException(host);
    };

    private static InetAddress ip(String text) throws UnknownHostException {
        return InetAddress.getByName(text);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void ipLiteralHostMatchesWithoutDns() throws Exception {
        TrapFilter filter = new TrapFilter("10.0.0.7", "", bytes("public"), NO_DNS);

        assertTrue(filter.matchesSource(ip("10.0.0.7")));
        assertFalse(filter.matchesSource(ip("10.0.0.8")));
    }

    @Test
    void allowlistAcceptsSingleAddressesAndCidrRanges() throws Exception {
        TrapFilter filter = new TrapFilter("", "192.168.1.5, 172.16.0.0/12, fd00::/8", bytes("c"), NO_DNS);

        assertTrue(filter.matchesSource(ip("192.168.1.5")));
        assertFalse(filter.matchesSource(ip("192.168.1.6")));
        assertTrue(filter.matchesSource(ip("172.31.255.1")));
        assertFalse(filter.matchesSource(ip("172.32.0.1")));
        assertTrue(filter.matchesSource(ip("fd12::1")));
        assertFalse(filter.matchesSource(ip("fe80::1")));
    }

    @Test
    void hostnameResolvesOnRefreshAndKeepsOldAddressesOnFailure() throws Exception {
        boolean[] failing = {false};
        TrapFilter filter = new TrapFilter("plc.example", "", bytes("c"), host -> {
            if (failing[0]) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {ip("10.1.1.1"), ip("10.1.1.2")};
        });
        assertFalse(filter.matchesSource(ip("10.1.1.1")), "nothing resolved before the first refresh");

        filter.refreshSources();
        assertTrue(filter.matchesSource(ip("10.1.1.2")));
        assertNull(filter.resolveError());

        failing[0] = true;
        filter.refreshSources();
        assertTrue(filter.matchesSource(ip("10.1.1.2")), "old addresses are kept");
        assertNotNull(filter.resolveError());
    }

    @Test
    void communityMustMatchExactlyAndEmptyNeverAuthenticates() {
        TrapFilter filter = new TrapFilter("10.0.0.7", "", bytes("s3cret"), NO_DNS);

        assertTrue(filter.authenticate(bytes("s3cret")));
        assertFalse(filter.authenticate(bytes("s3cret ")));
        assertFalse(filter.authenticate(bytes("S3CRET")));
        assertFalse(filter.authenticate(null));
        assertFalse(new TrapFilter("10.0.0.7", "", new byte[0], NO_DNS).authenticate(new byte[0]));
    }

    @Test
    void invalidAllowlistEntriesAreIgnored() throws Exception {
        TrapFilter filter = new TrapFilter("", "bogus, 10.0.0.0/99, 10.9.9.9", bytes("c"), NO_DNS);

        assertTrue(filter.matchesSource(ip("10.9.9.9")));
        assertFalse(filter.matchesSource(ip("10.0.0.1")));
        assertEquals(false, filter.matchesSource(ip("10.0.0.2")));
    }

    @Test
    void ipv4MappedIpv6CidrKeepsItsMeaning() throws Exception {
        TrapFilter filter = new TrapFilter("", "::ffff:192.0.2.0/120, ::ffff:10.0.0.0/64", bytes("c"), NO_DNS);

        assertTrue(filter.matchesSource(ip("192.0.2.77")));
        assertFalse(filter.matchesSource(ip("192.0.3.1")));
        assertFalse(filter.matchesSource(ip("10.0.0.1")), "a mapped prefix shorter than /96 is not representable");
    }
}
