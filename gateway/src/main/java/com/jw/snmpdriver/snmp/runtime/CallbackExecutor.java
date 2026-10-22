package com.jw.snmpdriver.snmp.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared bounded completion capacity, reserved before sending a network request. */
public final class CallbackExecutor {
    private static final Logger logger = LoggerFactory.getLogger(CallbackExecutor.class);
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private final Executor executor;
    private final ThreadPoolExecutor pool;
    private final int capacity;
    private final Set<Ticket> pending = new HashSet<>();
    private boolean shutdown;

    public CallbackExecutor() {
        this(4, 4096);
    }

    CallbackExecutor(int threads, int capacity) {
        this.capacity = capacity;
        pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "snmp-callback-" + THREAD_ID.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor = pool;
        pool.prestartAllCoreThreads();
    }

    // Deterministic coordinator tests keep the existing fake-client/virtual-time seam.
    CallbackExecutor(Executor executor, int capacity) {
        this.executor = Objects.requireNonNull(executor);
        this.capacity = capacity;
        pool = null;
    }

    SerialExecutor serialExecutor() {
        return new SerialExecutor();
    }

    /** Fails waiting and queued completions before the exclusively owned pool is stopped. */
    public void shutdown() {
        List<Ticket> cancelled;
        synchronized (this) {
            shutdown = true;
            cancelled = cancelPending(null);
            if (pool != null) {
                pool.shutdown();
            }
        }
        notifyCancelled(cancelled, new CancellationException("SNMP callback executor shutdown"));
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return pool == null || pool.awaitTermination(timeout, unit);
    }

    public void shutdownNow() {
        shutdown();
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private List<Ticket> cancelPending(SerialExecutor lane) {
        List<Ticket> cancelled = new ArrayList<>();
        for (Ticket ticket : List.copyOf(pending)) {
            if ((lane == null || ticket.lane == lane) && !ticket.running) {
                pending.remove(ticket);
                ticket.done = true;
                ticket.lane.ready.remove(ticket);
                cancelled.add(ticket);
            }
        }
        return cancelled;
    }

    private static void notifyCancelled(List<Ticket> cancelled, Throwable failure) {
        cancelled.forEach(ticket -> ticket.fail(failure));
    }

    final class SerialExecutor {
        // Every ready entry owns a shared reservation, so this queue is bounded too.
        private final ArrayDeque<Ticket> ready = new ArrayDeque<>();
        private final Runnable runner = this::drain;
        private boolean draining;
        private boolean closed;

        Ticket reserve(Consumer<Throwable> onFailure) {
            synchronized (CallbackExecutor.this) {
                if (shutdown || closed || pending.size() >= capacity) {
                    throw new RejectedExecutionException("SNMP callback capacity unavailable");
                }
                Ticket ticket = new Ticket(this, onFailure);
                pending.add(ticket);
                return ticket;
            }
        }

        void close() {
            List<Ticket> cancelled;
            synchronized (CallbackExecutor.this) {
                closed = true;
                cancelled = cancelPending(this);
                if (pool != null && pool.remove(runner)) {
                    draining = false;
                }
            }
            notifyCancelled(cancelled, new CancellationException("SNMP Device callback generation closed"));
        }

        private void drain() {
            while (true) {
                Ticket ticket;
                synchronized (CallbackExecutor.this) {
                    ticket = ready.pollFirst();
                    if (ticket == null) {
                        draining = false;
                        return;
                    }
                    ticket.running = true;
                }
                try {
                    ticket.action.run();
                } catch (Throwable error) {
                    ticket.fail(error);
                } finally {
                    synchronized (CallbackExecutor.this) {
                        ticket.done = true;
                        pending.remove(ticket);
                    }
                }
            }
        }
    }

    final class Ticket {
        private final SerialExecutor lane;
        private final Consumer<Throwable> onFailure;
        private Runnable action;
        private boolean running;
        private boolean done;

        private Ticket(SerialExecutor lane, Consumer<Throwable> onFailure) {
            this.lane = lane;
            this.onFailure = onFailure;
        }

        private void fail(Throwable error) {
            try {
                onFailure.accept(error);
            } catch (Throwable notificationError) {
                logger.error("SNMP callback failure notification failed", notificationError);
            }
        }

        void complete(Runnable action) {
            synchronized (CallbackExecutor.this) {
                if (done || this.action != null) {
                    return;
                }
                this.action = action;
                lane.ready.addLast(this);
                if (!lane.draining) {
                    lane.draining = true;
                    // At most capacity reservations exist. Each queued runner owns one,
                    // so the pool queue cannot fill independently of admission.
                    // Shutdown uses this same monitor and cancels tickets before the pool.
                    executor.execute(lane.runner);
                }
            }
        }
    }
}
