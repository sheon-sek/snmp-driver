package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TrapLicenseWatchTest {
    private final CapturingScheduler scheduler = new CapturingScheduler();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void watchersRunOnlyWhenTheLicenseVerdictChanges() {
        AtomicReference<LicenseGate.Verdict> verdict = new AtomicReference<>(LicenseGate.Verdict.LICENSED);
        TrapListenerRegistry registry = new TrapListenerRegistry((address, port) -> () -> { }, scheduler, verdict::get);
        AtomicInteger refreshed = new AtomicInteger();
        registry.acquire("a", "127.0.0.1", 39001, refreshed::incrementAndGet);

        scheduler.fire();
        assertEquals(0, refreshed.get(), "an unchanged License must not refresh anything");

        verdict.set(LicenseGate.Verdict.NOT_AVAILABLE);
        scheduler.fire();
        assertEquals(1, refreshed.get());

        scheduler.fire();
        assertEquals(1, refreshed.get(), "the same verdict is reported once");

        verdict.set(LicenseGate.Verdict.LICENSED);
        scheduler.fire();
        assertEquals(2, refreshed.get());

        registry.closeAll();
        assertTrue(scheduler.watches.get(0).isCancelled(), "closeAll stops the License watch");
    }

    @Test
    void registryWithoutALicenseSupplierSchedulesNothing() {
        TrapListenerRegistry registry = new TrapListenerRegistry((address, port) -> () -> { }, scheduler);

        registry.acquire("a", "127.0.0.1", 39002, () -> { });

        assertEquals(0, scheduler.watches.size());
        registry.closeAll();
    }

    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        final List<Watch> watches = new ArrayList<>();

        CapturingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit) {
            Watch watch = new Watch(command);
            watches.add(watch);
            return watch;
        }

        void fire() {
            watches.forEach(watch -> {
                if (!watch.isCancelled()) {
                    watch.command.run();
                }
            });
        }
    }

    private static final class Watch extends FutureTask<Object> implements ScheduledFuture<Object> {
        final Runnable command;

        Watch(Runnable command) {
            super(command, null);
            this.command = command;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }
}
