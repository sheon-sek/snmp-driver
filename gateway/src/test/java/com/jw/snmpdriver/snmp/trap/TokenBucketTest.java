package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void allowsTwiceTheRateAsBurstThenRefillsAtTheRate() {
        long[] clock = {0};
        TokenBucket bucket = new TokenBucket(10, () -> clock[0]);

        int burst = 0;
        while (bucket.tryAcquire()) {
            burst++;
        }
        assertEquals(20, burst);
        assertFalse(bucket.tryAcquire());

        clock[0] += TimeUnit.MILLISECONDS.toNanos(100); // one token at 10/s
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void idleTimeNeverBuildsUpMoreThanTheBurst() {
        long[] clock = {0};
        TokenBucket bucket = new TokenBucket(5, () -> clock[0]);
        clock[0] += TimeUnit.HOURS.toNanos(1);

        int burst = 0;
        while (bucket.tryAcquire()) {
            burst++;
        }
        assertEquals(10, burst);
    }
}
