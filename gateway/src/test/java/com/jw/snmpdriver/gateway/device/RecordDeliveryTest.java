package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Record Delivery lifecycle edges (#49, from the #48 review) that a Trap listener cannot time precisely. */
class RecordDeliveryTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void recordOfferedAfterStopIsCountedAsDroppedAndNeverDelivered() throws Exception {
        Handler handler = new Handler();
        RecordDelivery delivery = delivery("dev", 100, handler, () -> pool);
        delivery.start();
        delivery.offer("{\"sequence\":1}", 1);
        await(() -> delivery.deliveredCount() == 1);

        delivery.stop();
        delivery.offer("{\"sequence\":2}", 2);
        Thread.sleep(100);

        assertEquals(List.of("{\"sequence\":1}"), handler.records);
        assertEquals(1, delivery.droppedCount());
        assertEquals(0, delivery.queueDepth());
        assertReconciled(delivery, 2);
    }

    @Test
    void unavailablePoolDropsAndCountsRecordsAndReportsIt() {
        Handler handler = new Handler();
        RecordDelivery delivery = delivery("dev", 100, handler, () -> null);
        delivery.start();
        for (int i = 1; i <= 5; i++) {
            delivery.offer("{\"sequence\":" + i + "}", i);
        }

        assertEquals(List.of(), handler.records);
        assertEquals(5, delivery.droppedCount());
        assertEquals(0, delivery.queueDepth());
        assertReconciled(delivery, 5);
        assertTrue(delivery.lastError().contains("线程池不可用"), delivery.lastError());
    }

    @Test
    void rejectingPoolDropsAndCountsRecords() {
        Handler handler = new Handler();
        RecordDelivery delivery = delivery("dev", 100, handler, () -> command -> {
            throw new RejectedExecutionException("shut down");
        });
        delivery.start();
        delivery.offer("{\"sequence\":1}", 1);
        delivery.offer("{\"sequence\":2}", 2);

        assertEquals(2, delivery.droppedCount());
        assertEquals(0, delivery.queueDepth());
        assertTrue(delivery.lastError().contains("线程池不可用"), delivery.lastError());
    }

    /** A Device rebuilt by a configuration save must not call the function while the old instance's call runs. */
    @Test
    void newInstanceOfTheSameDeviceWaitsForTheOldInstancesRunningCall() throws Exception {
        CountDownLatch oldCallStarted = new CountDownLatch(1);
        CountDownLatch releaseOldCall = new CountDownLatch(1);
        Handler oldHandler = new Handler() {
            @Override
            public Result invoke(String project, String function, String record) {
                oldCallStarted.countDown();
                try {
                    releaseOldCall.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return super.invoke(project, function, record);
            }
        };
        RecordDelivery old = delivery("rebuilt", 100, oldHandler, () -> pool);
        old.start();
        old.offer("{\"sequence\":1}", 1);
        assertTrue(oldCallStarted.await(15, TimeUnit.SECONDS));
        old.stop();

        Handler newHandler = new Handler();
        RecordDelivery replacement = delivery("rebuilt", 100, newHandler, () -> pool);
        replacement.start();
        replacement.offer("{\"sequence\":1}", 1);
        Thread.sleep(300);
        assertEquals(List.of(), newHandler.records, "new instance called while the old call was still running");

        releaseOldCall.countDown();
        await(() -> newHandler.records.size() == 1);
        assertEquals(1, oldHandler.records.size());
    }

    @Test
    void otherDevicesAreNotHeldUpByASlowDevice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Handler slow = new Handler() {
            @Override
            public Result invoke(String project, String function, String record) {
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return super.invoke(project, function, record);
            }
        };
        Handler fast = new Handler();
        RecordDelivery slowDevice = delivery("slow", 100, slow, () -> pool);
        RecordDelivery fastDevice = delivery("fast", 100, fast, () -> pool);
        slowDevice.start();
        fastDevice.start();
        slowDevice.offer("{\"sequence\":1}", 1);
        fastDevice.offer("{\"sequence\":1}", 1);

        await(() -> fast.records.size() == 1);
        release.countDown();
        await(() -> slow.records.size() == 1);
    }

    private RecordDelivery delivery(String device, int capacity, Handler handler,
                                    java.util.function.Supplier<java.util.concurrent.Executor> executor) {
        return new RecordDelivery(device, "project", "snmp.handlers.onNotification", capacity, handler, executor);
    }

    private static void assertReconciled(RecordDelivery delivery, long accepted) {
        assertEquals(accepted, delivery.deliveredCount() + delivery.droppedCount() + delivery.queueDepth());
    }

    private static class Handler implements RecordHandlerInvoker {
        final List<String> records = new CopyOnWriteArrayList<>();

        @Override
        public Result invoke(String project, String function, String record) {
            records.add(record);
            return Result.SUCCESS;
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(10);
        }
    }
}
