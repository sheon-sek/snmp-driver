package com.jw.snmpdriver.snmp.trap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TrapListenerRegistryTest {
    private final ManualScheduler scheduler = new ManualScheduler();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void firstAcquireBindsAndLastReleaseFreesPort() throws Exception {
        int port = freePort();
        TrapListenerRegistry registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);

        TrapListenerHandle a = registry.acquire("dev-a", "127.0.0.1", port);
        TrapListenerHandle b = registry.acquire("dev-b", "127.0.0.1", port);

        assertEquals(TrapListenerState.BOUND, a.state());
        assertEquals(TrapListenerState.BOUND, b.state());
        assertEquals("127.0.0.1:" + port, a.endpoint());
        assertFalse(canBind(port));
        // 收到的包直接丢弃，不影响 listener
        try (DatagramSocket sender = new DatagramSocket()) {
            byte[] junk = {1, 2, 3};
            sender.send(new DatagramPacket(junk, junk.length, InetAddress.getLoopbackAddress(), port));
        }

        a.release();
        assertFalse(canBind(port));
        assertEquals(TrapListenerState.BOUND, b.state());
        b.release();
        assertTrue(canBind(port));
        assertEquals(TrapListenerState.CLOSED, b.state());
        registry.closeAll();
    }

    @Test
    void bindFailureDegradesAndRecoversWithinThirtySeconds() throws Exception {
        int port = freePort();
        TrapListenerRegistry registry = new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler);

        TrapListenerHandle handle;
        try (DatagramSocket occupier = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
            handle = registry.acquire("dev-a", "127.0.0.1", port);
            assertEquals(TrapListenerState.FAILED, handle.state());
            assertFalse(handle.lastError().isBlank());
            assertEquals(1, scheduler.pendingCount());
            assertEquals(30, scheduler.lastDelaySeconds());

            scheduler.runPending();
            assertEquals(TrapListenerState.FAILED, handle.state());
            assertEquals(1, scheduler.pendingCount(), "失败后继续固定间隔重试");
            assertEquals(30, scheduler.lastDelaySeconds());
        }

        scheduler.runPending();
        assertEquals(TrapListenerState.BOUND, handle.state());
        assertNull(handle.lastError());
        assertEquals(0, scheduler.pendingCount());
        registry.closeAll();
    }

    @Test
    void retryOutcomeNotifiesWatchersUntilReleased() throws Exception {
        FakeBinder binder = new FakeBinder();
        binder.failures = 1;
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);
        int[] changes = new int[1];

        TrapListenerHandle handle = registry.acquire("dev-a", "0.0.0.0", 10162, () -> changes[0]++);
        assertEquals(TrapListenerState.FAILED, handle.state());
        assertEquals(0, changes[0], "首次 bind 结果由 acquire 调用方直接读取，不回调");

        scheduler.runPending();
        assertEquals(TrapListenerState.BOUND, handle.state());
        assertEquals(1, changes[0]);

        TrapListenerHandle other = registry.acquire("dev-b", "0.0.0.0", 10162);
        handle.release();
        other.release();
        assertEquals(1, changes[0]);
    }

    @Test
    void releasingLastReferenceCancelsRetry() throws Exception {
        FakeBinder binder = new FakeBinder();
        binder.failures = 100;
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);

        TrapListenerHandle handle = registry.acquire("dev-a", "0.0.0.0", 10162);
        assertEquals(1, scheduler.pendingCount());
        handle.release();

        assertEquals(0, scheduler.pendingCount());
        int attempts = binder.attempts;
        scheduler.runPending();
        assertEquals(attempts, binder.attempts);
        assertEquals(TrapListenerState.CLOSED, handle.state());
    }

    @Test
    void differentBindAddressOnSamePortIsRejectedWithConflictInfo() throws Exception {
        FakeBinder binder = new FakeBinder();
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);

        TrapListenerHandle first = registry.acquire("dev-a", "0.0.0.0", 10162);
        TrapListenerHandle second = registry.acquire("dev-b", "127.0.0.1", 10162);

        assertEquals(TrapListenerState.BOUND, first.state());
        assertTrue(second.isRejected());
        assertTrue(second.rejection().contains("dev-a"), second.rejection());
        assertTrue(second.rejection().contains("0.0.0.0"), second.rejection());
        assertEquals(1, binder.bound.size());

        // 被拒绝者 release 不影响 listener
        second.release();
        assertEquals(TrapListenerState.BOUND, first.state());
        // 同地址写法不同但解析相同，视为一致
        assertFalse(registry.acquire("dev-c", "0.0.0.0", 10162).isRejected());
        // 不同端口互不影响
        assertFalse(registry.acquire("dev-d", "127.0.0.1", 10163).isRejected());
    }

    @Test
    void rejectedDeviceCanAcquireAfterFirstArrivalReleases() {
        FakeBinder binder = new FakeBinder();
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);
        TrapListenerHandle first = registry.acquire("dev-a", "0.0.0.0", 10162);
        TrapListenerHandle rejected = registry.acquire("dev-b", "127.0.0.1", 10162);
        assertTrue(rejected.isRejected());

        first.release();
        TrapListenerHandle retried = registry.acquire("dev-b", "127.0.0.1", 10162);

        assertFalse(retried.isRejected());
        assertEquals(TrapListenerState.BOUND, retried.state());
        assertEquals("127.0.0.1:10162", retried.endpoint());
    }

    @Test
    void invalidBindAddressIsRejectedWithoutDnsLookup() {
        TrapListenerRegistry registry = new TrapListenerRegistry(new FakeBinder(), scheduler);

        TrapListenerHandle handle = registry.acquire("dev-a", "not-an-ip.example", 10162);

        assertTrue(handle.isRejected());
        assertTrue(handle.rejection().contains("not-an-ip.example"));
    }

    @Test
    void closeAllReleasesEveryListenerAndStopsRetries() throws Exception {
        FakeBinder binder = new FakeBinder();
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);
        TrapListenerHandle a = registry.acquire("dev-a", "0.0.0.0", 10162);
        TrapListenerHandle b = registry.acquire("dev-b", "0.0.0.0", 10163);
        binder.failures = 100;
        TrapListenerHandle c = registry.acquire("dev-c", "0.0.0.0", 10164);
        assertEquals(1, scheduler.pendingCount());

        registry.closeAll();

        assertEquals(0, binder.open());
        assertEquals(0, scheduler.pendingCount());
        assertEquals(TrapListenerState.CLOSED, a.state());
        assertEquals(TrapListenerState.CLOSED, b.state());
        assertEquals(TrapListenerState.CLOSED, c.state());
        a.release(); // closeAll 之后的 release 无副作用
    }

    @Test
    void rescheduleRejectedBySchedulerLeavesListenerFailed() throws Exception {
        FakeBinder binder = new FakeBinder();
        binder.failures = 100;
        scheduler.shutdownNow();
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);

        TrapListenerHandle handle = registry.acquire("dev-a", "0.0.0.0", 10162);

        assertEquals(TrapListenerState.FAILED, handle.state());
    }

    @Test
    void creationFailureIsReportedNotThrown() {
        List<RuntimeException> reported = new ArrayList<>();

        TrapListenerRegistry registry = TrapListenerRegistry.tryCreate(() -> {
            throw new IllegalStateException("injected");
        }, reported::add);

        assertNull(registry);
        assertEquals(1, reported.size());
    }

    @Test
    void reconfiguredDeviceReacquiresSamePortAfterOldHandleReleased() {
        FakeBinder binder = new FakeBinder();
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);
        TrapListenerHandle old = registry.acquire("dev-a", "0.0.0.0", 10162);
        assertEquals(1, binder.open());

        old.release(); // 旧设备 shutdown
        TrapListenerHandle fresh = registry.acquire("dev-a", "127.0.0.1", 10162);

        assertFalse(fresh.isRejected());
        assertEquals(TrapListenerState.BOUND, fresh.state());
        assertEquals("127.0.0.1:10162", fresh.endpoint());
        assertEquals(1, binder.open(), "旧 socket 已关闭，只剩新 listener");
        assertEquals(TrapListenerState.CLOSED, old.state());
    }

    @Test
    void conflictNamesCurrentHolderNotReleasedFirstArrival() {
        TrapListenerRegistry registry = new TrapListenerRegistry(new FakeBinder(), scheduler);
        TrapListenerHandle a = registry.acquire("dev-a", "0.0.0.0", 10162);
        TrapListenerHandle b = registry.acquire("dev-b", "0.0.0.0", 10162);
        a.release();

        TrapListenerHandle c = registry.acquire("dev-c", "127.0.0.1", 10162);

        assertTrue(c.isRejected());
        assertFalse(c.rejection().contains("dev-a"), c.rejection());
        assertTrue(c.rejection().contains("dev-b"), c.rejection());
        assertTrue(c.rejection().contains("0.0.0.0"), c.rejection());
        b.release();
    }

    @Test
    void bindAddressMustBeStrictIpLiteral() {
        TrapListenerRegistry registry = new TrapListenerRegistry(new FakeBinder(), scheduler);

        assertTrue(registry.acquire("dev-a", "1.2.3", 10162).isRejected(), "缺段的 IPv4 简写不是点分十进制");
        assertTrue(registry.acquire("dev-a", "deadbeef", 10162).isRejected());
        assertFalse(registry.acquire("dev-a", " ::1 ", 10163).isRejected());
    }

    @Test
    void retryBindRunsOutsideRegistryLockAndDropsResultIfReleasedMeanwhile() throws Exception {
        FakeBinder binder = new FakeBinder();
        binder.failures = 1;
        TrapListenerRegistry registry = new TrapListenerRegistry(binder, scheduler);
        TrapListenerHandle handle = registry.acquire("dev-a", "0.0.0.0", 10162);
        assertEquals(TrapListenerState.FAILED, handle.state());
        binder.gate = new java.util.concurrent.CountDownLatch(1);
        binder.entered = new java.util.concurrent.CountDownLatch(1);

        Thread retrying = new Thread(scheduler::runPending);
        retrying.start();
        assertTrue(binder.entered.await(5, TimeUnit.SECONDS));

        // bind 正在阻塞：registry 仍可服务其他端口与 release
        java.util.concurrent.CompletableFuture<Void> others = java.util.concurrent.CompletableFuture.runAsync(() -> {
            registry.acquire("dev-b", "0.0.0.0", 10163);
            handle.release();
        });
        others.get(5, TimeUnit.SECONDS);

        binder.gate.countDown();
        retrying.join(5000);
        assertEquals(TrapListenerState.CLOSED, handle.state());
        assertEquals(1, binder.open(), "迟到的 bind 结果被关闭，只剩 10163 的 listener");
        assertEquals(0, scheduler.pendingCount());
    }

    private static int freePort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static boolean canBind(int port) {
        try (DatagramSocket socket = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static final class FakeBinder implements ListenerBinder {
        int failures;
        int attempts;
        volatile java.util.concurrent.CountDownLatch gate;
        volatile java.util.concurrent.CountDownLatch entered;
        final List<Closeable> bound = new ArrayList<>();
        private final List<boolean[]> closed = new ArrayList<>();

        @Override
        public Closeable bind(InetAddress address, int port) throws IOException {
            attempts++;
            java.util.concurrent.CountDownLatch blocker = gate;
            if (blocker != null && port == 10162) {
                entered.countDown();
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failures > 0) {
                failures--;
                throw new IOException("Address already in use");
            }
            boolean[] flag = new boolean[1];
            closed.add(flag);
            Closeable handle = () -> flag[0] = true;
            bound.add(handle);
            return handle;
        }

        int open() {
            return (int) closed.stream().filter(flag -> !flag[0]).count();
        }
    }

    /** Captures one-shot schedule() calls; time never advances on its own. */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Task> tasks = new ArrayList<>();
        private long lastDelaySeconds;

        ManualScheduler() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (isShutdown()) {
                throw new RejectedExecutionException("shut down");
            }
            lastDelaySeconds = unit.toSeconds(delay);
            Task task = new Task(command);
            tasks.add(task);
            return task;
        }

        synchronized int pendingCount() {
            return (int) tasks.stream().filter(task -> !task.isCancelled()).count();
        }

        long lastDelaySeconds() {
            return lastDelaySeconds;
        }

        void runPending() {
            List<Task> ready;
            synchronized (this) {
                ready = new ArrayList<>(tasks);
                tasks.clear();
            }
            ready.stream().filter(task -> !task.isCancelled()).forEach(Task::run);
        }

        private static final class Task extends java.util.concurrent.FutureTask<Object>
                implements ScheduledFuture<Object> {
            Task(Runnable command) {
                super(command, null);
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
}
