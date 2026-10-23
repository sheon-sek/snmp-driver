package com.jw.snmpdriver.snmp.trap;

import java.util.function.LongSupplier;

/** Per-Device limiter: {@code ratePerSecond} refill, burst of twice that, never blocks. */
public final class TokenBucket {
    private final double ratePerNano;
    private final double capacity;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;

    public TokenBucket(int ratePerSecond) {
        this(ratePerSecond, System::nanoTime);
    }

    TokenBucket(int ratePerSecond, LongSupplier nanoClock) {
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.capacity = 2.0 * ratePerSecond;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefill = nanoClock.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastRefill) * ratePerNano);
        lastRefill = now;
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }
}
