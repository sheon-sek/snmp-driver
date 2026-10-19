package com.jw.snmpdriver.snmp.runtime;

import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-threaded virtual time; no threads, sockets or wall-clock waiting. */
final class ControlledScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Task<?>> tasks = new PriorityQueue<>();
    private long nowNanos = TimeUnit.SECONDS.toNanos(1);
    private long sequence;
    private boolean shutdown;

    long nanoTime() {
        return nowNanos;
    }

    void runReady() {
        advanceBy(0, TimeUnit.NANOSECONDS);
    }

    void advanceBy(long amount, TimeUnit unit) {
        long target = nowNanos + unit.toNanos(amount);
        int remaining = 10_000;
        while (!tasks.isEmpty() && tasks.peek().dueNanos <= target) {
            if (--remaining == 0) {
                throw new AssertionError("Scheduler did not become idle within 10000 tasks");
            }
            Task<?> task = tasks.remove();
            nowNanos = task.dueNanos;
            task.run();
        }
        nowNanos = target;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(java.util.concurrent.Executors.callable(command, null), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
        if (shutdown) {
            throw new RejectedExecutionException("Controlled scheduler is shut down");
        }
        Task<V> task = new Task<>(command, nowNanos + Math.max(0, unit.toNanos(delay)), sequence++);
        tasks.add(task);
        return task;
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("Only one-shot scheduling is used by the coordinator");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Only one-shot scheduling is used by the coordinator");
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        List<Runnable> pending = List.copyOf(tasks);
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
        return pending;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && tasks.stream().allMatch(Task::isDone);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }

    private final class Task<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
        private final long dueNanos;
        private final long order;

        private Task(Callable<V> command, long dueNanos, long order) {
            super(command);
            this.dueNanos = dueNanos;
            this.order = order;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueNanos - nowNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            Task<?> task = (Task<?>) other;
            int byTime = Long.compare(dueNanos, task.dueNanos);
            return byTime == 0 ? Long.compare(order, task.order) : byTime;
        }

        @Override
        public boolean isPeriodic() {
            return false;
        }

        @Override
        public void run() {
            super.run();
            if (!isCancelled()) {
                try {
                    get();
                } catch (Exception error) {
                    throw new AssertionError("Scheduled task failed", error);
                }
            }
        }
    }
}
