package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import org.snmp4j.smi.UdpAddress;

public final class DnsResolver implements AutoCloseable {
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final HostLookup hostLookup;

    public DnsResolver() {
        this(InetAddress::getAllByName);
    }

    DnsResolver(HostLookup hostLookup) {
        this.hostLookup = hostLookup;
    }

    @FunctionalInterface
    interface HostLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            runnable -> {
                Thread thread = new Thread(runnable, "snmp-dns-" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    CompletionStage<UdpAddress> resolve(SnmpEndpoint endpoint, boolean ipv6Available) {
        String key = ipv6Available + ":" + endpoint.ipFamily() + ":" + endpoint.host() + ":" + endpoint.port();
        long now = System.nanoTime();
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAtNanos() > now) {
            return CompletableFuture.completedFuture(cached.address());
        }

        try {
            return CompletableFuture.supplyAsync(() -> resolveAddress(endpoint, ipv6Available), executor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .thenApply(address -> {
                        evictExpiredAndBound(System.nanoTime());
                        cache.put(key, new CacheEntry(address, System.nanoTime() + TTL_NANOS));
                        return address;
                    })
                    .exceptionallyCompose(error -> CompletableFuture.failedFuture(
                            describeFailure(endpoint, error)
                    ));
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new RejectedExecutionException(
                    "SNMP DNS resolver queue is full host=" + endpoint.host(),
                    error
            ));
        }
    }

    private UdpAddress resolveAddress(SnmpEndpoint endpoint, boolean ipv6Available) {
        try {
            InetAddress[] candidates = hostLookup.lookup(endpoint.host());
            if (!ipv6Available && (endpoint.ipFamily() == SNMPDeviceConfig.IpFamily.IPV6
                    || (endpoint.ipFamily() == SNMPDeviceConfig.IpFamily.AUTO
                    && Arrays.stream(candidates).noneMatch(Inet4Address.class::isInstance)
                    && Arrays.stream(candidates).anyMatch(Inet6Address.class::isInstance)))) {
                throw new IOException("SNMP IPv6 transport is unavailable host=" + endpoint.host()
                        + " ipFamily=" + endpoint.ipFamily()
                        + "; enable IPv6 on the Gateway host or configure an IPv4 destination");
            }
            InetAddress address = Arrays.stream(candidates)
                    .filter(candidate -> matchesFamily(endpoint.ipFamily(), candidate))
                    .filter(candidate -> ipv6Available || candidate instanceof Inet4Address)
                    .findFirst()
                    .orElseThrow(() -> new UnknownHostException("No address matches requested IP family"));
            return new UdpAddress(address, endpoint.port());
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private static boolean matchesFamily(SNMPDeviceConfig.IpFamily family, InetAddress address) {
        return family == SNMPDeviceConfig.IpFamily.AUTO
                || (family == SNMPDeviceConfig.IpFamily.IPV4 && address instanceof Inet4Address)
                || (family == SNMPDeviceConfig.IpFamily.IPV6 && address instanceof Inet6Address);
    }

    private static Throwable describeFailure(SnmpEndpoint endpoint, Throwable error) {
        Throwable root = SnmpFailureClassifier.unwrap(error);
        if (root instanceof TimeoutException) {
            return new SnmpDnsTimeoutException(
                    "DNS resolution timed out host=" + endpoint.host()
                            + " ipFamily=" + endpoint.ipFamily()
                            + " timeoutMs=2000"
            );
        }
        if (root instanceof UnknownHostException) {
            UnknownHostException described = new UnknownHostException(
                    "DNS resolution failed host=" + endpoint.host()
                            + " ipFamily=" + endpoint.ipFamily()
                            + " reason=" + safeMessage(root)
            );
            described.initCause(root);
            return described;
        }
        return root == null ? error : root;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private void evictExpiredAndBound(long nowNanos) {
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= nowNanos);
        if (cache.size() < 1_024) {
            return;
        }
        cache.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().expiresAtNanos()))
                .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }

    @Override
    public void close() {
        executor.shutdownNow();
        cache.clear();
    }

    private record CacheEntry(UdpAddress address, long expiresAtNanos) {
    }
}

