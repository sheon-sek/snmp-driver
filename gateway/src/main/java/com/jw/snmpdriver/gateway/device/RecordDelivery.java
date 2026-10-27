package com.jw.snmpdriver.gateway.device;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record Delivery of one Device (ADR 0010). Each accepted Notification's Record waits in a bounded FIFO queue and
 * is handed to the configured project function in arrival order, one call at a time, on a pool shared by all
 * Devices. {@link #offer} only enqueues, so the listener worker, the Inform ACK (ADR 0004) and polling never wait
 * for a script.
 *
 * <p>Every offered Record ends in exactly one count, so
 * {@code offered = deliveredCount + droppedCount + queueDepth} (handler failures are counted by #50).
 */
final class RecordDelivery {
    /** Records handled per turn before the pool thread is handed to other Devices. */
    private static final int BATCH = 64;

    /**
     * One lock per Device name, shared by every instance: a Device rebuilt by a configuration save waits for the
     * old instance's running call, so the same Device never calls its function twice at once.
     */
    private static final ConcurrentMap<String, Object> CALL_LOCKS = new ConcurrentHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(RecordDelivery.class);

    private record PendingRecord(String json, long sequence) {
    }

    private final String deviceName;
    private final String project;
    private final String function;
    private final int capacity;
    private final RecordHandlerInvoker invoker;
    private final Supplier<Executor> pool;
    private final Object callLock;
    private final BlockingQueue<PendingRecord> queue;
    /** Guards {@link #running} so a Record offered while stopping is either drained by stop() or counted. */
    private final Object lifecycle = new Object();
    /** True while a drain task is queued or running: at most one handler call per instance at a time. */
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** Records queued or in the handler right now. */
    private final AtomicLong outstanding = new AtomicLong();
    private final AtomicBoolean overflowing = new AtomicBoolean();
    private final AtomicBoolean poolUnavailable = new AtomicBoolean();
    private volatile String lastError = "";
    private boolean running;
    /** Last failure written to the log; repeats are not logged again until a call succeeds. */
    private RecordHandlerInvoker.Result lastLoggedFailure;

    RecordDelivery(String deviceName, String project, String function, int capacity, RecordHandlerInvoker invoker,
                   Supplier<Executor> pool) {
        this.deviceName = deviceName;
        this.project = project;
        this.function = function;
        this.capacity = Math.max(1, capacity);
        this.invoker = Objects.requireNonNull(invoker);
        this.pool = Objects.requireNonNull(pool);
        this.callLock = CALL_LOCKS.computeIfAbsent(deviceName, name -> new Object());
        this.queue = new LinkedBlockingQueue<>(this.capacity);
    }

    /** Null when the Device has no Record Handler Function: such a Device produces no Records at all. */
    static RecordDelivery forConfig(String deviceName, SNMPDeviceConfig config,
                                    Supplier<RecordHandlerInvoker> invoker, Supplier<Executor> pool) {
        String function = config.recordHandlerFunction() == null ? "" : config.recordHandlerFunction().strip();
        if (function.isEmpty()) {
            return null;
        }
        String project = config.recordHandlerProject() == null ? "" : config.recordHandlerProject().strip();
        return new RecordDelivery(deviceName, project, function, config.recordQueueCapacity(), invoker.get(), pool);
    }

    void start() {
        synchronized (lifecycle) {
            running = true;
        }
    }

    /** Queued Records are counted as dropped; a call already running finishes on its own. */
    void stop() {
        synchronized (lifecycle) {
            running = false;
            dropQueued();
        }
    }

    /** Handler calls that returned normally. */
    long deliveredCount() {
        return delivered.get();
    }

    /** Records discarded: queue full, Device stopped, or the delivery pool unavailable. */
    long droppedCount() {
        return dropped.get();
    }

    /** Records waiting in the queue plus the one in the handler. */
    long queueDepth() {
        return outstanding.get();
    }

    /** Latest Record Delivery problem for {@code Traps/LastError}; empty when there has been none. */
    String lastError() {
        return lastError;
    }

    /** Called on the listener worker, in arrival order, for every Notification this Device accepted. */
    void offer(String record, long sequence) {
        boolean full;
        synchronized (lifecycle) {
            if (!running) {
                dropped.incrementAndGet();
                return;
            }
            // counted first: a running drain may take and finish the Record before offer() returns
            outstanding.incrementAndGet();
            full = !queue.offer(new PendingRecord(record, sequence));
            if (full) {
                outstanding.decrementAndGet();
                dropped.incrementAndGet();
            }
        }
        if (full) {
            if (overflowing.compareAndSet(false, true)) {
                lastError = "Record 队列已满（容量 " + capacity + "），新到的 Record 被丢弃并计入 RecordDroppedCount"
                        + "（首个丢弃 sequence=" + sequence + "）；处理函数可能过慢，请检查 RecordQueueDepth "
                        + "或调大 Record Queue Capacity。";
                logger.warn("Record queue full, dropping newly arrived Records device={} capacity={} sequence={} "
                        + "category=record", deviceName, capacity, sequence);
            }
            return;
        }
        overflowing.set(false);
        schedule();
    }

    private void schedule() {
        if (queue.isEmpty() || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            Executor executor = pool.get();
            if (executor == null) {
                throw new RejectedExecutionException("Record Delivery pool is not running");
            }
            executor.execute(this::drain);
            poolUnavailable.set(false);
        } catch (RejectedExecutionException error) {
            scheduled.set(false);
            // nothing would ever take these Records: count them now instead of leaving them queued
            dropQueued();
            if (poolUnavailable.compareAndSet(false, true)) {
                lastError = "Record 投递线程池不可用（Gateway 正在启动或停止），Record 被丢弃并计入 RecordDroppedCount。";
                logger.warn("Record Delivery could not be scheduled, dropping queued Records device={} "
                        + "category=record message={}", deviceName, error.getMessage());
            }
        }
    }

    private void dropQueued() {
        while (queue.poll() != null) {
            dropped.incrementAndGet();
            outstanding.decrementAndGet();
        }
    }

    private void drain() {
        try {
            for (int handled = 0; handled < BATCH && isRunning(); handled++) {
                PendingRecord record = queue.poll();
                if (record == null) {
                    break;
                }
                try {
                    synchronized (callLock) {
                        deliver(record);
                    }
                } finally {
                    outstanding.decrementAndGet();
                }
            }
        } finally {
            scheduled.set(false);
            // a Record offered while this turn was finishing would otherwise wait for the next offer
            schedule();
        }
    }

    private boolean isRunning() {
        synchronized (lifecycle) {
            return running;
        }
    }

    private void deliver(PendingRecord record) {
        RecordHandlerInvoker.Result result;
        try {
            result = invoker.invoke(project, function, record.json());
        } catch (RuntimeException error) {
            result = RecordHandlerInvoker.Result.handlerFailed(String.valueOf(error));
        }
        if (result.outcome() == RecordHandlerInvoker.Outcome.SUCCEEDED) {
            delivered.incrementAndGet();
            lastLoggedFailure = null;
            return;
        }
        if (!result.equals(lastLoggedFailure)) {
            lastLoggedFailure = result;
            logger.warn("Record handler call failed device={} sequence={} project={} function={} outcome={} "
                    + "detail={}", deviceName, record.sequence(), project, function, result.outcome(),
                    result.detail());
        }
    }
}
