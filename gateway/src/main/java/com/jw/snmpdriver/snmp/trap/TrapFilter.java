package com.jw.snmpdriver.snmp.trap;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-Device source and community check. Sources are the Device host's resolved addresses plus the
 * allowlist; matching never looks at a v1 agent-addr.
 */
public final class TrapFilter {
    @FunctionalInterface
    public interface HostLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final String host;
    private final List<Cidr> allowlist;
    private final byte[] community;
    private final HostLookup lookup;
    private volatile Set<InetAddress> hostAddresses = Set.of();
    private volatile String resolveError;

    /** {@code allowlist} is comma-separated IP or CIDR entries; invalid entries are ignored (validate rejects them). */
    public TrapFilter(String host, String allowlist, byte[] community, HostLookup lookup) {
        this.host = host == null ? "" : host.trim();
        this.allowlist = parseAllowlist(allowlist);
        this.community = community == null ? new byte[0] : community.clone();
        this.lookup = lookup;
        InetAddress literal = TrapListenerRegistry.parseIpLiteral(this.host);
        if (literal != null) {
            hostAddresses = Set.of(literal);
        }
    }

    public boolean matchesSource(InetAddress source) {
        if (hostAddresses.contains(source)) {
            return true;
        }
        for (Cidr cidr : allowlist) {
            if (cidr.contains(source)) {
                return true;
            }
        }
        return false;
    }

    /** Constant-time; an empty configured community never authenticates anything. */
    public boolean authenticate(byte[] presented) {
        return community.length > 0 && presented != null && MessageDigest.isEqual(community, presented);
    }

    /** Re-resolves the host; on failure the previous addresses stay and {@link #resolveError()} explains. */
    public void refreshSources() {
        if (host.isEmpty() || TrapListenerRegistry.parseIpLiteral(host) != null) {
            return;
        }
        try {
            InetAddress[] resolved = lookup.lookup(host);
            hostAddresses = new HashSet<>(List.of(resolved));
            resolveError = null;
        } catch (UnknownHostException | RuntimeException error) {
            resolveError = "无法解析设备地址 " + host + "，沿用上次解析结果：" + error.getMessage();
        }
    }

    /** Null when the last refresh succeeded. */
    public String resolveError() {
        return resolveError;
    }

    private static List<Cidr> parseAllowlist(String text) {
        List<Cidr> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String entry : text.split(",")) {
            String trimmed = entry.trim();
            int slash = trimmed.indexOf('/');
            InetAddress address = TrapListenerRegistry.parseIpLiteral(slash < 0 ? trimmed : trimmed.substring(0, slash));
            if (address == null) {
                continue;
            }
            int max = address.getAddress().length * 8;
            int prefix = max;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(trimmed.substring(slash + 1));
                } catch (NumberFormatException error) {
                    continue;
                }
                if (prefix < 0 || prefix > max) {
                    continue;
                }
            }
            result.add(new Cidr(address.getAddress(), prefix));
        }
        return result;
    }

    private record Cidr(byte[] network, int prefix) {
        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (bytes[full] & mask) == (network[full] & mask);
        }
    }
}
