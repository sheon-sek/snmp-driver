package com.jw.snmpdriver.gateway.device;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record Delivery of one Device (ADR 0010). Each accepted Notification's Record is handed to the configured
 * project function in arrival order, one call at a time, on a pool shared by all Devices. {@link #offer} only
 * enqueues, so the listener worker, the Inform ACK (ADR 0004) and polling never wait for a script.
 */
final class RecordDelivery {
    /** Records handled per turn before the pool thread is handed to other Devices. */
    private static final int BATCH = 64;

    private static final Logger logger = LoggerFactory.getLogger(RecordDelivery.class);

    private final String deviceName;
    private final String project;
    private final String function;
    private final RecordHandlerInvoker invoker;
    private final Supplier<Executor> pool;
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    /** True while a drain task is queued or running: at most one handler call per Device at a time. */
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong delivered = new AtomicLong();
    private volatile boolean running;
    /** Last failure written to the log; repeats are not logged again until a call succeeds. */
    private RecordHandlerInvoker.Result lastLoggedFailure;

    RecordDelivery(String deviceName, String project, String function, RecordHandlerInvoker invoker,
                   Supplier<Executor> pool) {
        this.deviceName = deviceName;
        this.project = project;
        this.function = function;
        this.invoker = Objects.requireNonNull(invoker);
        this.pool = Objects.requireNonNull(pool);
    }

    /** Null when the Device has no Record Handler Function: such a Device produces no Records at all. */
    static RecordDelivery forConfig(String deviceName, SNMPDeviceConfig config,
                                    Supplier<RecordHandlerInvoker> invoker, Supplier<Executor> pool) {
        String function = config.recordHandlerFunction() == null ? "" : config.recordHandlerFunction().strip();
        if (function.isEmpty()) {
            return null;
        }
        String project = config.recordHandlerProject() == null ? "" : config.recordHandlerProject().strip();
        return new RecordDelivery(deviceName, project, function, invoker.get(), pool);
    }

    void start() {
        running = true;
    }

    /** Pending Records are discarded; a call already running finishes on its own. */
    void stop() {
        running = false;
        queue.clear();
    }

    /** Handler calls that returned normally. */
    long deliveredCount() {
        return delivered.get();
    }

    /** Called on the listener worker, in arrival order, for every Notification this Device accepted. */
    void offer(String record) {
        if (!running) {
            return;
        }
        queue.add(record);
        schedule();
    }

    private void schedule() {
        if (!running || queue.isEmpty() || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            Executor executor = pool.get();
            if (executor == null) {
                throw new RejectedExecutionException("Record Delivery pool is not running");
            }
            executor.execute(this::drain);
        } catch (RejectedExecutionException error) {
            scheduled.set(false);
            logger.warn("Record Delivery could not be scheduled device={} category=record message={}",
                    deviceName, error.getMessage());
        }
    }

    private void drain() {
        try {
            for (int handled = 0; handled < BATCH && running; handled++) {
                String record = queue.poll();
                if (record == null) {
                    break;
                }
                deliver(record);
            }
        } finally {
            scheduled.set(false);
            // a Record offered while this turn was finishing would otherwise wait for the next offer
            schedule();
        }
    }

    private void deliver(String record) {
        RecordHandlerInvoker.Result result;
        try {
            result = invoker.invoke(project, function, record);
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
            logger.warn("Record handler call failed device={} project={} function={} outcome={} detail={}",
                    deviceName, project, function, result.outcome(), result.detail());
        }
    }
}
