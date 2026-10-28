package com.jw.snmpdriver.gateway.device;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
 * {@code offered = deliveredCount + handlerErrorCount + droppedCount + queueDepth}.
 */
final class RecordDelivery {
    /** Records handled per turn before the pool thread is handed to other Devices. */
    private static final int BATCH = 64;

    private static final Logger logger = LoggerFactory.getLogger(RecordDelivery.class);

    private record PendingRecord(String json, long sequence) {
    }

    /**
     * Serializes the handler calls of every instance of one Device name: a Device rebuilt by a configuration save
     * never calls its function while the old instance's call still runs. An instance that finds another one inside
     * a call parks instead of holding a pool thread, and is rescheduled when that call ends, so one stuck Device
     * cannot use up the threads shared by all Devices. An entry lives only while a call runs or an instance is
     * parked. A renamed Device is a different name here (ADR 0010).
     */
    private static final class CallGate {
        /** Guarded by itself. */
        private static final Map<String, CallGate> GATES = new HashMap<>();

        private RecordDelivery caller;
        private final Set<RecordDelivery> parked = new LinkedHashSet<>();

        /** True when {@code delivery} may call now; otherwise it is parked until the running call ends. */
        static boolean enter(String name, RecordDelivery delivery) {
            synchronized (GATES) {
                CallGate gate = GATES.computeIfAbsent(name, key -> new CallGate());
                if (gate.caller == null) {
                    gate.caller = delivery;
                    gate.parked.remove(delivery);
                    return true;
                }
                gate.parked.add(delivery);
                return false;
            }
        }

        /** Ends {@code delivery}'s call; returns the instances parked meanwhile, which must be rescheduled. */
        static List<RecordDelivery> exit(String name, RecordDelivery delivery) {
            synchronized (GATES) {
                CallGate gate = GATES.get(name);
                if (gate == null || gate.caller != delivery) {
                    return List.of();
                }
                GATES.remove(name);
                return List.copyOf(gate.parked);
            }
        }

        /** A stopped instance stops waiting; the entry goes once nothing runs or waits. */
        static void leave(String name, RecordDelivery delivery) {
            synchronized (GATES) {
                CallGate gate = GATES.get(name);
                if (gate != null && gate.parked.remove(delivery) && gate.caller == null) {
                    GATES.remove(name);
                }
            }
        }

        static boolean tracks(String name) {
            synchronized (GATES) {
                return GATES.containsKey(name);
            }
        }
    }

    private final String deviceName;
    private final String project;
    private final String function;
    private final int capacity;
    private final RecordHandlerInvoker invoker;
    private final Supplier<Executor> pool;
    private final BlockingQueue<PendingRecord> queue;
    /** Guards {@link #running} so a Record offered while stopping is either drained by stop() or counted. */
    private final Object lifecycle = new Object();
    /** True while a drain task is queued or running: at most one handler call per instance at a time. */
    private final AtomicBoolean scheduled = new AtomicBoolean();
    /** Set when another instance's call ended while this one was parking: its drain must schedule again. */
    private final AtomicBoolean wake = new AtomicBoolean();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong handlerErrors = new AtomicLong();
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
        this.queue = new LinkedBlockingQueue<>(this.capacity);
    }

    /** True while some instance of {@code deviceName} is inside a handler call or waiting for one to end. */
    static boolean holdsCallState(String deviceName) {
        return CallGate.tracks(deviceName);
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
        CallGate.leave(deviceName, this);
    }

    /** Handler calls that returned normally. */
    long deliveredCount() {
        return delivered.get();
    }

    long handlerErrorCount() {
        return handlerErrors.get();
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
        wake.set(false);
        boolean parked = false;
        try {
            for (int handled = 0; handled < BATCH && isRunning() && !queue.isEmpty(); handled++) {
                if (!CallGate.enter(deviceName, this)) {
                    // another instance of this Device is inside a call: give the thread back, resume when it ends
                    parked = true;
                    break;
                }
                PendingRecord record = queue.poll();
                try {
                    if (record != null) {
                        try {
                            deliver(record);
                        } finally {
                            outstanding.decrementAndGet();
                        }
                    }
                } finally {
                    for (RecordDelivery waiting : CallGate.exit(deviceName, this)) {
                        waiting.wake.set(true);
                        waiting.schedule();
                    }
                }
                if (record == null) {
                    break;
                }
            }
        } finally {
            scheduled.set(false);
            // a Record offered while this turn was finishing would otherwise wait for the next offer;
            // a parked turn reschedules only if the blocking call already ended
            if (!parked || wake.getAndSet(false)) {
                schedule();
            }
        }
    }

    private boolean isRunning() {
        synchronized (lifecycle) {
            return running;
        }
    }

    private void deliver(PendingRecord record) {
        RecordHandlerInvoker.Result result;
        long started = System.nanoTime();
        try {
            result = invoker.invoke(project, function, record.json());
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (VirtualMachineError fatal) {
            // Script recursion can overflow the stack; OOM and other VM failures must still escape.
            if (!(fatal instanceof StackOverflowError)) {
                throw fatal;
            }
            result = RecordHandlerInvoker.Result.handlerFailed(String.valueOf(fatal));
        } catch (Throwable error) {
            result = RecordHandlerInvoker.Result.handlerFailed(String.valueOf(error));
        } finally {
            long elapsed = System.nanoTime() - started;
            if (elapsed > TimeUnit.SECONDS.toNanos(5)) {
                logger.warn("Slow record handler call device={} sequence={} project={} function={} elapsedMillis={}",
                        deviceName, record.sequence(), project, function,
                        TimeUnit.NANOSECONDS.toMillis(elapsed));
            }
        }
        if (result.outcome() == RecordHandlerInvoker.Outcome.SUCCEEDED) {
            delivered.incrementAndGet();
            lastLoggedFailure = null;
            // the function works again: an earlier failure, overflow or pool notice no longer applies
            lastError = "";
            return;
        }
        if (result.outcome() == RecordHandlerInvoker.Outcome.HANDLER_FAILED) {
            handlerErrors.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
        lastError = "Record handler project='" + project + "' function='" + function
                + "' sequence=" + record.sequence() + ": " + result.detail();
        if (!result.equals(lastLoggedFailure)) {
            lastLoggedFailure = result;
            logger.warn("Record handler call failed device={} sequence={} project={} function={} outcome={} "
                    + "detail={}", deviceName, record.sequence(), project, function, result.outcome(),
                    result.detail());
        }
    }
}
