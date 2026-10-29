package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /**
     * A hung handler plus repeated configuration saves must not park every shared pool thread: replacements of the
     * stuck Device wait without holding a thread, so other Devices keep receiving Records.
     */
    @Test
    void replacementsOfAHungDeviceDoNotOccupySharedThreads() throws Exception {
        CountDownLatch hungCallStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Handler hung = blockingHandler(hungCallStarted, release);
        RecordDelivery stuck = delivery("stuck", 100, hung, () -> pool);
        stuck.start();
        stuck.offer("{\"sequence\":1}", 1);
        assertTrue(hungCallStarted.await(15, TimeUnit.SECONDS));
        stuck.stop();

        List<Handler> replacements = new ArrayList<>();
        for (int save = 0; save < 4; save++) {
            Handler handler = new Handler();
            replacements.add(handler);
            RecordDelivery replacement = delivery("stuck", 100, handler, () -> pool);
            replacement.start();
            replacement.offer("{\"sequence\":1}", 1);
            Thread.sleep(50);
            if (save < 3) {
                replacement.stop();
            }
        }

        Handler other = new Handler();
        RecordDelivery otherDevice = delivery("other", 100, other, () -> pool);
        otherDevice.start();
        otherDevice.offer("{\"sequence\":1}", 1);
        await(() -> other.records.size() == 1);
        assertEquals(List.of(), replacements.get(3).records, "replacement called while the hung call runs");

        release.countDown();
        await(() -> replacements.get(3).records.size() == 1);
        assertEquals(1, hung.records.size());
    }

    /** Deleted or renamed Devices must not leave per-name call state behind once their calls have ended. */
    @Test
    void callStateOfADeviceIsReleasedOnceItsCallsEnd() throws Exception {
        CountDownLatch oldCallStarted = new CountDownLatch(1);
        CountDownLatch releaseOldCall = new CountDownLatch(1);
        RecordDelivery old = delivery("deleted", 100, blockingHandler(oldCallStarted, releaseOldCall), () -> pool);
        old.start();
        old.offer("{\"sequence\":1}", 1);
        assertTrue(oldCallStarted.await(15, TimeUnit.SECONDS));
        old.stop();
        RecordDelivery parked = delivery("deleted", 100, new Handler(), () -> pool);
        parked.start();
        parked.offer("{\"sequence\":1}", 1);
        Thread.sleep(100);
        parked.stop();

        releaseOldCall.countDown();
        await(() -> old.queueDepth() == 0);
        await(() -> !RecordDelivery.holdsCallState("deleted"));

        Handler handler = new Handler();
        RecordDelivery quick = delivery("quick", 100, handler, () -> pool);
        quick.start();
        quick.offer("{\"sequence\":1}", 1);
        await(() -> handler.records.size() == 1);
        quick.stop();
        await(() -> !RecordDelivery.holdsCallState("quick"));
    }

    /** A function that keeps raising the same error logs it once until a call succeeds; every failure counts. */
    @Test
    void repeatedIdenticalHandlerFailureIsLoggedOnceUntilACallSucceeds() throws Exception {
        List<RecordHandlerInvoker.Result> results = new CopyOnWriteArrayList<>(List.of(
                RecordHandlerInvoker.Result.handlerFailed("ValueError: bad"),
                RecordHandlerInvoker.Result.handlerFailed("ValueError: bad"),
                RecordHandlerInvoker.Result.handlerFailed("ValueError: bad"),
                RecordHandlerInvoker.Result.SUCCESS,
                RecordHandlerInvoker.Result.handlerFailed("ValueError: bad")));
        RecordHandlerInvoker scripted = (project, function, record) -> results.remove(0);
        RecordDelivery delivery = new RecordDelivery("noisy", "project", "snmp.handlers.onNotification", 100,
                scripted, () -> pool);

        String log = captureStandardError(() -> {
            delivery.start();
            for (int i = 1; i <= 5; i++) {
                delivery.offer("{\"sequence\":" + i + "}", i);
            }
            await(() -> delivery.queueDepth() == 0);
        });

        assertEquals(4, delivery.handlerErrorCount());
        assertEquals(1, delivery.deliveredCount());
        assertEquals(2, log.split("Record handler call failed", -1).length - 1, log);
    }

    @Test
    void successfulCallClearsTheRecordError() throws Exception {
        List<RecordHandlerInvoker.Result> results = new CopyOnWriteArrayList<>(List.of(
                RecordHandlerInvoker.Result.handlerFailed("ValueError: bad"),
                RecordHandlerInvoker.Result.SUCCESS));
        RecordHandlerInvoker scripted = (project, function, record) -> results.remove(0);
        RecordDelivery delivery = new RecordDelivery("recovering", "project", "snmp.handlers.onNotification", 100,
                scripted, () -> pool);
        delivery.start();

        delivery.offer("{\"sequence\":1}", 1);
        await(() -> delivery.handlerErrorCount() == 1);
        assertTrue(delivery.lastError().contains("ValueError: bad"), delivery.lastError());

        delivery.offer("{\"sequence\":2}", 2);
        await(() -> delivery.deliveredCount() == 1);
        assertEquals("", delivery.lastError());
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

    /** Signals {@code started} on each call, then holds the calling thread until {@code release}. */
    private static Handler blockingHandler(CountDownLatch started, CountDownLatch release) {
        return new Handler() {
            @Override
            public Result invoke(String project, String function, String record) {
                started.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return super.invoke(project, function, record);
            }
        };
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** The test runtime logs through slf4j-simple, which writes to the current {@code System.err}. */
    private static String captureStandardError(Action action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
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
