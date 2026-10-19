package com.jw.snmpdriver.snmp.runtime;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.jw.snmpdriver.gateway.device.SNMPDeviceConfig;
import com.jw.snmpdriver.snmp.address.NumericOid;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.snmp4j.PDU;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SharedPollCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(SharedPollCoordinator.class);
    private static final long FAILURE_SUMMARY_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_PENDING_SETS_PER_DEVICE = 256;

    private final SnmpRequestClient requestClient;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoClock;
    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    public SharedPollCoordinator(
            SnmpRequestClient requestClient,
            ScheduledExecutorService scheduler
    ) {
        this(requestClient, scheduler, System::nanoTime);
    }

    SharedPollCoordinator(
            SnmpRequestClient requestClient,
            ScheduledExecutorService scheduler,
            LongSupplier nanoClock
    ) {
        this.requestClient = Objects.requireNonNull(requestClient);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        accepting.set(true);
        logger.info("SNMP poll coordinator started");
    }

    public void stop() {
        accepting.set(false);
        if (!running.getAndSet(false)) {
            return;
        }
        devices.values().forEach(state -> state.close("runtime shutdown"));
        devices.clear();
        logger.info("SNMP poll coordinator stopped");
    }

    public DevicePollHandle registerDevice(SnmpEndpoint endpoint) {
        if (!accepting.get()) {
            throw new IllegalStateException("SNMP poll coordinator is not accepting devices");
        }

        DeviceState state = new DeviceState(
                Objects.requireNonNull(endpoint),
                generationSequence.incrementAndGet()
        );
        DeviceState previous = devices.put(endpoint.deviceId(), state);
        if (previous != null) {
            previous.close("device reconfigured");
        }
        logger.info(
                "SNMP device runtime registered device={} host={} port={} version={} generation={}",
                endpoint.deviceId(), endpoint.host(), endpoint.port(), endpoint.version(), state.generation
        );
        return new Handle(state);
    }

    private boolean isCurrent(DeviceState state) {
        return running.get()
                && !state.closed
                && devices.get(state.endpoint.deviceId()) == state;
    }

    private void safeWake(DeviceState state) {
        try {
            state.wake();
        } catch (RuntimeException error) {
            synchronized (state) {
                state.recordFailure("SCHEDULER", null, error);
                state.entries.values().stream()
                        .filter(PollEntry::hasSubscribers)
                        .forEach(entry -> entry.publishFailure(error, nanoClock.getAsLong()));
                if (error instanceof RejectedExecutionException) {
                    state.failQueuedSets(error);
                } else {
                    try {
                        state.schedule(TimeUnit.SECONDS.toNanos(1));
                    } catch (RejectedExecutionException schedulingError) {
                        state.handleSchedulingFailure(schedulingError);
                    }
                }
            }
        }
    }

    private final class Handle implements DevicePollHandle {
        private final DeviceState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(DeviceState state) {
            this.state = state;
        }

        @Override
        public PollSubscription subscribe(
                ResolvedAddress address,
                long revisedIntervalMillis,
                Consumer<RawBinding> onValue,
                Consumer<Throwable> onFailure
        ) {
            if (closed.get() || !accepting.get()) {
                throw new CancellationException("SNMP Device poll handle is closed");
            }
            return state.subscribe(address, revisedIntervalMillis, onValue, onFailure);
        }

        @Override
        public CompletionStage<SetResult> set(
                ResolvedAddress address,
                Variable value,
                long deadlineNanos
        ) {
            if (closed.get() || !accepting.get()) {
                return CompletableFuture.failedFuture(
                        new CancellationException("SNMP Device poll handle is closed")
                );
            }
            return state.enqueueSet(address, value, deadlineNanos);
        }

        @Override
        public DevicePollSnapshot snapshot() {
            return state.snapshot();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                devices.remove(state.endpoint.deviceId(), state);
                state.close("device lease closed");
            }
        }
    }

    private final class DeviceState {
        private final SnmpEndpoint endpoint;
        private final long generation;
        private final DeviceCircuitBreaker breaker = new DeviceCircuitBreaker();
        private final Map<NumericOid, PollEntry> entries = new HashMap<>();
        private final ArrayDeque<SetCommand> setQueue = new ArrayDeque<>();
        private final int maxInflight;

        private ScheduledFuture<?> wakeTask;
        private int inFlight;
        private boolean preferSet = true;
        private volatile boolean closed;
        private Instant lastSuccessTime;
        private Instant lastFailureTime;
        private String lastError = "";
        private final Map<String, FailureLogState> failureLogs = new HashMap<>();
        private long communicationFailureStartedNanos;
        private boolean communicationFailureActive;

        private DeviceState(SnmpEndpoint endpoint, long generation) {
            this.endpoint = endpoint;
            this.generation = generation;
            this.maxInflight = endpoint.maxInflightPerDevice();
        }

        private synchronized PollSubscription subscribe(
                ResolvedAddress address,
                long intervalMillis,
                Consumer<RawBinding> onValue,
                Consumer<Throwable> onFailure
        ) {
            if (closed) {
                throw new CancellationException("SNMP Device generation is no longer registered");
            }
            if (intervalMillis < 1_000L) {
                throw new SnmpValueException(
                        StatusCodes.Bad_OutOfRange,
                        "interval",
                        "INVALID_INTERVAL: SNMP polling interval must be at least 1000 ms"
                );
            }

            PollEntry entry = entries.computeIfAbsent(
                    address.oid(),
                    oid -> new PollEntry(new PollKey(endpoint.deviceId(), oid), nanoClock)
            );
            Subscriber subscriber = entry.addSubscriber(intervalMillis, onValue, onFailure);
            try {
                schedule(0L);
            } catch (RejectedExecutionException error) {
                entry.removeSubscriber(subscriber);
                if (!entry.hasSubscribers()) {
                    entries.remove(address.oid(), entry);
                }
                handleSchedulingFailure(error);
                throw error;
            }
            return () -> removeSubscriber(address.oid(), entry, subscriber);
        }

        private synchronized void removeSubscriber(
                NumericOid oid,
                PollEntry entry,
                Subscriber subscriber
        ) {
            if (!subscriber.close()) {
                return;
            }
            entry.removeSubscriber(subscriber);
            if (!entry.hasSubscribers()) {
                entries.remove(oid, entry);
            }
            scheduleNext(nanoClock.getAsLong());
        }

        private synchronized CompletionStage<SetResult> enqueueSet(
                ResolvedAddress address,
                Variable value,
                long deadlineNanos
        ) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new CancellationException("SNMP Device is closed")
                );
            }
            if (deadlineNanos <= nanoClock.getAsLong()) {
                SnmpQueueTimeoutException failure = new SnmpQueueTimeoutException(
                        "SNMP SET queue deadline expired before enqueue"
                );
                recordFailure("SET", address.oid(), failure);
                return CompletableFuture.failedFuture(failure);
            }
            expireQueuedSets(nanoClock.getAsLong());
            if (setQueue.size() >= MAX_PENDING_SETS_PER_DEVICE) {
                RejectedExecutionException failure = new RejectedExecutionException(
                        "SNMP SET queue capacity exceeded device=" + endpoint.deviceId()
                                + " capacity=" + MAX_PENDING_SETS_PER_DEVICE
                );
                recordFailure("SET", address.oid(), failure);
                return CompletableFuture.failedFuture(failure);
            }
            SetCommand command = new SetCommand(address, value, deadlineNanos);
            setQueue.addLast(command);
            try {
                schedule(0L);
            } catch (RejectedExecutionException error) {
                handleSchedulingFailure(error);
            }
            return command.result;
        }

        private void wake() {
            synchronized (this) {
                wakeTask = null;
                if (!isCurrent(this)) {
                    return;
                }

                long now = nanoClock.getAsLong();
                expireQueuedSets(now);
                for (PollEntry entry : entries.values()) {
                    entry.publishCachedDue(now);
                }
                while (inFlight < maxInflight) {
                    if (setQueue.isEmpty()
                            && entries.values().stream().noneMatch(PollEntry::hasSubscribers)) {
                        break;
                    }
                    boolean normal = breaker.allowNormal(now);
                    boolean probe = !normal && breaker.tryHalfOpen(now);
                    if (probe) {
                        logger.info(
                                "SNMP circuit transition device={} host={} port={} version={} from=OPEN to=HALF_OPEN",
                                endpoint.deviceId(), endpoint.host(), endpoint.port(), endpoint.version()
                        );
                    }
                    if (!normal && !probe) {
                        break;
                    }

                    SetCommand set = null;
                    List<PollEntry> batch = List.of();
                    if (preferSet) {
                        set = pollSet(now);
                        if (set == null) {
                            batch = selectBatch(now);
                        }
                    } else {
                        batch = selectBatch(now);
                        if (batch.isEmpty()) {
                            set = pollSet(now);
                        }
                    }
                    if (set != null) {
                        preferSet = false;
                        inFlight++;
                        dispatchSet(set);
                    } else {
                        if (batch.isEmpty() && probe) {
                            batch = selectProbeBatch();
                        }
                        if (batch.isEmpty()) {
                            break;
                        }
                        preferSet = true;
                        inFlight++;
                        dispatchBatch(batch);
                    }

                    if (probe) {
                        break;
                    }
                    now = nanoClock.getAsLong();
                }
                scheduleNext(nanoClock.getAsLong());
            }
        }

        private void dispatchSet(SetCommand command) {
            try {
                CompletionStage<SetResult> request =
                        requestClient.set(endpoint, command.address.oid(), command.value);
                if (request == null) {
                    throw new IllegalStateException("SNMP request client returned a null SET stage");
                }
                request.whenComplete((result, error) -> completeSet(command, result, error));
            } catch (RuntimeException error) {
                completeSet(command, null, error);
            }
        }

        private void completeSet(SetCommand command, SetResult result, Throwable error) {
            synchronized (this) {
                inFlight = Math.max(0, inFlight - 1);
                if (!isCurrent(this)) {
                    command.result.completeExceptionally(
                            new CancellationException("SNMP Device generation changed")
                    );
                    return;
                }

                Throwable failure = unwrap(error);
                if (failure == null && result == null) {
                    failure = new IllegalStateException(
                            "SNMP SET completed successfully without a result"
                    );
                }
                if (failure == null) {
                    recordValidResponse("SET", command.address.oid());
                    recordSuccess("SET", command.address.oid());
                    command.result.complete(result);
                } else {
                    SnmpFailureClassifier.Failure classified =
                            SnmpFailureClassifier.classify(failure);
                    if (classified.circuitFailure()) {
                        recordCircuitFailure("SET", command.address.oid(), failure);
                    } else if (classified.legalResponse()) {
                        recordValidResponse("SET", command.address.oid());
                    }
                    recordFailure("SET", command.address.oid(), failure);
                    command.result.completeExceptionally(failure);
                }
                try {
                    schedule(0L);
                } catch (RejectedExecutionException schedulingError) {
                    handleSchedulingFailure(schedulingError);
                }
            }
        }

        private void dispatchBatch(List<PollEntry> batch) {
            executeBatch(batch).whenComplete((ignored, error) -> {
                synchronized (this) {
                    inFlight = Math.max(0, inFlight - 1);
                    if (!isCurrent(this)) {
                        return;
                    }
                    Throwable failure = unwrap(error);
                    if (failure != null) {
                        SnmpFailureClassifier.Failure classified =
                                SnmpFailureClassifier.classify(failure);
                        if (classified.circuitFailure()) {
                            recordCircuitFailure("GET", null, failure);
                        } else if (classified.legalResponse()) {
                            recordValidResponse("GET", null);
                        }
                        recordFailure("GET_BATCH", null, failure);
                        for (PollEntry entry : batch) {
                            finishFailure(entry, failure, false);
                        }
                    }
                    try {
                        schedule(0L);
                    } catch (RejectedExecutionException schedulingError) {
                        handleSchedulingFailure(schedulingError);
                    }
                }
            });
        }

        private CompletionStage<Void> executeBatch(List<PollEntry> batch) {
            try {
                List<NumericOid> oids =
                        batch.stream().map(entry -> entry.key.oid()).toList();
                CompletionStage<BatchResult> request = requestClient.getBatch(endpoint, oids);
                if (request == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "SNMP request client returned a null GET stage"
                    ));
                }
                return request.thenCompose(result -> processBatchResult(batch, result));
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        private CompletionStage<Void> processBatchResult(
                List<PollEntry> batch,
                BatchResult result
        ) {
            synchronized (this) {
                if (!isCurrent(this)) {
                    return CompletableFuture.completedFuture(null);
                }
                recordValidResponse("GET", null);

                if (result.errorStatus() == PDU.tooBig) {
                    if (batch.size() == 1) {
                        finishFailure(batch.get(0), protocol(result));
                        return CompletableFuture.completedFuture(null);
                    }
                    int middle = batch.size() / 2;
                    List<PollEntry> first = List.copyOf(batch.subList(0, middle));
                    List<PollEntry> second = List.copyOf(batch.subList(middle, batch.size()));
                    return executeBatch(first).thenCompose(ignored -> executeBatch(second));
                }

                if (endpoint.version() == SNMPDeviceConfig.Version.V1
                        && result.errorStatus() == PDU.noSuchName) {
                    int index = result.errorIndex() - 1;
                    if (index < 0 || index >= batch.size()) {
                        SnmpProtocolException failure = protocol(result);
                        batch.forEach(entry -> finishFailure(entry, failure));
                        return CompletableFuture.completedFuture(null);
                    }
                    PollEntry offender = batch.get(index);
                    offender.isolated = true;
                    finishFailure(offender, protocol(result));
                    List<PollEntry> remaining = new ArrayList<>(batch);
                    remaining.remove(index);
                    return remaining.isEmpty()
                            ? CompletableFuture.completedFuture(null)
                            : executeBatch(List.copyOf(remaining));
                }

                if (!result.successful()) {
                    SnmpProtocolException failure = protocol(result);
                    batch.forEach(entry -> finishFailure(entry, failure));
                    return CompletableFuture.completedFuture(null);
                }

                Map<NumericOid, RawBinding> byOid = new HashMap<>();
                Set<NumericOid> requestedOids = new HashSet<>();
                batch.forEach(entry -> requestedOids.add(entry.key.oid()));
                Set<NumericOid> duplicateOids = new HashSet<>();
                for (RawBinding binding : result.bindings()) {
                    if (!requestedOids.contains(binding.oid())) {
                        recordFailure(
                                "GET",
                                binding.oid(),
                                new SnmpProtocolException(
                                        PDU.genErr,
                                        0,
                                        "SNMP GET response contained an unrequested OID"
                                )
                        );
                        continue;
                    }
                    if (byOid.put(binding.oid(), binding) != null) {
                        duplicateOids.add(binding.oid());
                    }
                }
                for (PollEntry entry : batch) {
                    RawBinding binding = byOid.get(entry.key.oid());
                    if (duplicateOids.contains(entry.key.oid())) {
                        finishFailure(entry, new SnmpProtocolException(
                                PDU.genErr,
                                0,
                                "SNMP GET response contained duplicate bindings for the requested OID"
                        ));
                    } else if (binding == null) {
                        finishFailure(entry, new SnmpProtocolException(
                                PDU.genErr,
                                0,
                                "SNMP response omitted requested OID"
                        ));
                    } else if (isExceptionSyntax(binding.syntax())) {
                        finishFailure(entry, new SnmpProtocolException(
                                PDU.noSuchName,
                                0,
                                binding.value().toString()
                        ));
                    } else {
                        finishSuccess(entry, binding);
                    }
                }
                return CompletableFuture.completedFuture(null);
            }
        }

        private List<PollEntry> selectBatch(long nowNanos) {
            PollEntry isolated = entries.values().stream()
                    .filter(entry -> entry.isolated && entry.isDue(nowNanos))
                    .findFirst()
                    .orElse(null);
            if (isolated != null) {
                isolated.inFlight = true;
                return List.of(isolated);
            }

            int bytes = 160;
            List<PollEntry> batch = new ArrayList<>();
            for (PollEntry entry : entries.values()) {
                if (entry.isolated || !entry.isDue(nowNanos)) {
                    continue;
                }
                int estimate = 24 + entry.key.oid().canonical().length() * 2;
                if (!batch.isEmpty()
                        && (batch.size() >= endpoint.maxOidsPerRequest()
                        || bytes + estimate > endpoint.maxRequestBytes())) {
                    break;
                }
                entry.inFlight = true;
                batch.add(entry);
                bytes += estimate;
            }
            return List.copyOf(batch);
        }

        private List<PollEntry> selectProbeBatch() {
            PollEntry entry = entries.values().stream()
                    .filter(candidate -> candidate.hasSubscribers() && !candidate.inFlight)
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                return List.of();
            }
            entry.inFlight = true;
            return List.of(entry);
        }

        private SetCommand pollSet(long nowNanos) {
            expireQueuedSets(nowNanos);
            while (!setQueue.isEmpty()) {
                SetCommand command = setQueue.removeFirst();
                if (!command.result.isDone()) {
                    return command;
                }
            }
            return null;
        }

        private void expireQueuedSets(long nowNanos) {
            setQueue.removeIf(command -> {
                if (command.result.isDone()) {
                    return true;
                }
                if (command.deadlineNanos <= nowNanos) {
                    SnmpQueueTimeoutException failure = new SnmpQueueTimeoutException(
                            "SNMP SET queue deadline expired before dispatch"
                    );
                    command.result.completeExceptionally(failure);
                    recordFailure("SET", command.address.oid(), failure);
                    return true;
                }
                return false;
            });
        }

        private void finishSuccess(PollEntry entry, RawBinding binding) {
            if (!entry.inFlight) {
                return;
            }
            long now = nanoClock.getAsLong();
            entry.inFlight = false;
            entry.isolated = false;
            entry.nextDueNanos = saturatedAdd(
                    now,
                    TimeUnit.MILLISECONDS.toNanos(entry.intervalMillis)
            );
            recordSuccess("GET", entry.key.oid());
            entry.publishValue(binding, now);
        }

        private void finishFailure(PollEntry entry, Throwable failure) {
            finishFailure(entry, failure, true);
        }

        private void finishFailure(
                PollEntry entry,
                Throwable failure,
                boolean record
        ) {
            if (!entry.inFlight) {
                return;
            }
            long now = nanoClock.getAsLong();
            entry.inFlight = false;
            entry.nextDueNanos = saturatedAdd(
                    now,
                    TimeUnit.MILLISECONDS.toNanos(entry.intervalMillis)
            );
            if (record) {
                recordFailure("GET", entry.key.oid(), failure);
            }
            entry.publishFailure(failure, now);
        }

        private void recordSuccess(String operation, NumericOid oid) {
            Instant now = Instant.now();
            lastSuccessTime = now;
            String oidText = oid == null ? "-" : oid.canonical();
            String recoveryPrefix = operation + "|" + oidText + "|";
            boolean operationRecovered = failureLogs.keySet().removeIf(
                    key -> key.startsWith(recoveryPrefix)
            );
            if (operationRecovered && !communicationFailureActive) {
                logger.info(
                        "SNMP operation recovered device={} host={} port={} op={} oid={} version={}",
                        endpoint.deviceId(), endpoint.host(), endpoint.port(), operation, oidText,
                        endpoint.version()
                );
            }
            if (communicationFailureActive) {
                long durationMillis = TimeUnit.NANOSECONDS.toMillis(
                        Math.max(0L, nanoClock.getAsLong() - communicationFailureStartedNanos)
                );
                logger.info(
                        "SNMP communication recovered device={} host={} port={} op={} oid={} version={} durationMs={}",
                        endpoint.deviceId(),
                        endpoint.host(),
                        endpoint.port(),
                        operation,
                        oid == null ? "-" : oid.canonical(),
                        endpoint.version(),
                        durationMillis
                );
                communicationFailureStartedNanos = 0L;
                communicationFailureActive = false;
                failureLogs.clear();
            }
        }

        private void recordFailure(String operation, NumericOid oid, Throwable failure) {
            Throwable root = unwrap(failure);
            SnmpFailureClassifier.Failure classified = SnmpFailureClassifier.classify(root);
            String category = classified.category();
            String message = classified.message();
            lastFailureTime = Instant.now();
            lastError = classified.diagnosticMessage();
            if (classified.circuitFailure() && !communicationFailureActive) {
                communicationFailureStartedNanos = nanoClock.getAsLong();
                communicationFailureActive = true;
            }

            long now = nanoClock.getAsLong();
            String oidText = oid == null ? "-" : oid.canonical();
            String key = operation + "|" + oidText + "|" + category + "|" + message;
            FailureLogState logState = failureLogs.get(key);
            if (logState == null) {
                if (failureLogs.size() >= 512) {
                    failureLogs.clear();
                }
                failureLogs.put(key, new FailureLogState(now, 0L));
                logFailure(operation, oidText, classified, root);
                return;
            }

            logState.suppressed++;
            if (now - logState.lastWarnNanos >= FAILURE_SUMMARY_NANOS) {
                logger.warn(
                        "SNMP repeated failures device={} host={} port={} op={} oid={} version={} category={} message={} suppressed={}",
                        endpoint.deviceId(),
                        endpoint.host(),
                        endpoint.port(),
                        operation,
                        oid == null ? "-" : oid.canonical(),
                        endpoint.version(),
                        category,
                        message,
                        logState.suppressed
                );
                logState.suppressed = 0L;
                logState.lastWarnNanos = now;
            }
        }

        private void logFailure(
                String operation,
                String oid,
                SnmpFailureClassifier.Failure failure,
                Throwable root
        ) {
            String template = "SNMP operation failed device={} host={} port={} op={} oid={} version={} category={} message={}";
            if (failure.unexpected() && SnmpFailureClassifier.safeToLogStack(root)) {
                logger.error(
                        template,
                        endpoint.deviceId(), endpoint.host(), endpoint.port(), operation, oid,
                        endpoint.version(), failure.category(), failure.message(), root
                );
            } else if (failure.unexpected()) {
                logger.error(
                        template,
                        endpoint.deviceId(), endpoint.host(), endpoint.port(), operation, oid,
                        endpoint.version(), failure.category(), failure.message()
                );
            } else {
                logger.warn(
                        template,
                        endpoint.deviceId(), endpoint.host(), endpoint.port(), operation, oid,
                        endpoint.version(), failure.category(), failure.message()
                );
            }
        }

        private void recordCircuitFailure(
                String operation,
                NumericOid oid,
                Throwable failure
        ) {
            DeviceCircuitBreaker.State before = breaker.state();
            breaker.onTransportFailure(
                    nanoClock.getAsLong(),
                    ThreadLocalRandom.current().nextLong(0L, 501L)
            );
            DeviceCircuitBreaker.State after = breaker.state();
            if (before != after) {
                SnmpFailureClassifier.Failure classified = SnmpFailureClassifier.classify(failure);
                logger.warn(
                        "SNMP circuit transition device={} host={} port={} op={} oid={} version={} from={} to={} category={} message={}",
                        endpoint.deviceId(), endpoint.host(), endpoint.port(), operation,
                        oid == null ? "-" : oid.canonical(), endpoint.version(), before, after,
                        classified.category(), classified.message()
                );
            }
        }

        private void recordValidResponse(String operation, NumericOid oid) {
            DeviceCircuitBreaker.State before = breaker.state();
            breaker.onValidResponse();
            DeviceCircuitBreaker.State after = breaker.state();
            if (before != after) {
                logger.info(
                        "SNMP circuit transition device={} host={} port={} op={} oid={} version={} from={} to={}",
                        endpoint.deviceId(), endpoint.host(), endpoint.port(), operation,
                        oid == null ? "-" : oid.canonical(), endpoint.version(), before, after
                );
            }
        }

        private void handleSchedulingFailure(RejectedExecutionException error) {
            recordFailure("SCHEDULER", null, error);
            entries.values().stream()
                    .filter(PollEntry::hasSubscribers)
                    .forEach(entry -> entry.publishFailure(error, nanoClock.getAsLong()));
            failQueuedSets(error);
        }

        private void failQueuedSets(Throwable error) {
            setQueue.forEach(command -> command.result.completeExceptionally(error));
            setQueue.clear();
        }

        private synchronized DevicePollSnapshot snapshot() {
            int subscriptions = entries.values().stream()
                    .mapToInt(PollEntry::subscriberCount)
                    .sum();
            double minimum = entries.values().stream()
                    .filter(PollEntry::hasSubscribers)
                    .mapToLong(entry -> entry.intervalMillis)
                    .min()
                    .stream()
                    .mapToDouble(value -> (double) value)
                    .findFirst()
                    .orElse(Double.NaN);
            return new DevicePollSnapshot(
                    subscriptions,
                    entries.size(),
                    minimum,
                    breaker.state(),
                    lastSuccessTime,
                    lastFailureTime,
                    lastError
            );
        }

        private synchronized void scheduleNext(long nowNanos) {
            if (!isCurrent(this)) {
                return;
            }
            DeviceCircuitBreaker.State circuitState = breaker.state();
            if (circuitState == DeviceCircuitBreaker.State.OPEN) {
                if (setQueue.isEmpty() && entries.isEmpty()) {
                    return;
                }
                long delay = breaker.nanosUntilProbe(nowNanos);
                for (PollEntry entry : entries.values()) {
                    long nextDelivery = entry.nextDeliveryDueNanos();
                    if (nextDelivery != Long.MAX_VALUE) {
                        delay = Math.min(delay, Math.max(0L, nextDelivery - nowNanos));
                    }
                }
                for (SetCommand command : setQueue) {
                    delay = Math.min(delay, Math.max(0L, command.deadlineNanos - nowNanos));
                }
                schedule(delay);
                return;
            }
            if (circuitState == DeviceCircuitBreaker.State.HALF_OPEN) {
                return;
            }

            long delay = Long.MAX_VALUE;
            for (PollEntry entry : entries.values()) {
                long nextDelivery = entry.nextDeliveryDueNanos();
                if (nextDelivery != Long.MAX_VALUE) {
                    delay = Math.min(delay, Math.max(0L, nextDelivery - nowNanos));
                }
            }
            if (!setQueue.isEmpty()) {
                if (inFlight < maxInflight) {
                    delay = 0L;
                } else {
                    for (SetCommand command : setQueue) {
                        delay = Math.min(delay, Math.max(0L, command.deadlineNanos - nowNanos));
                    }
                }
            }
            if (inFlight < maxInflight) {
                for (PollEntry entry : entries.values()) {
                    if (!entry.inFlight && entry.hasSubscribers()) {
                        delay = Math.min(delay, Math.max(0L, entry.nextDueNanos - nowNanos));
                    }
                }
            }
            if (delay != Long.MAX_VALUE) {
                schedule(delay);
            }
        }

        private synchronized void schedule(long delayNanos) {
            if (!isCurrent(this)) {
                return;
            }
            long delay = Math.max(0L, delayNanos);
            if (wakeTask != null && !wakeTask.isDone()) {
                long existing = wakeTask.getDelay(TimeUnit.NANOSECONDS);
                if (existing <= delay) {
                    return;
                }
                wakeTask.cancel(false);
            }
            try {
                wakeTask = scheduler.schedule(
                        () -> safeWake(this),
                        delay,
                        TimeUnit.NANOSECONDS
                );
            } catch (RejectedExecutionException error) {
                throw new RejectedExecutionException(
                        "SNMP scheduler rejected wake-up device=" + endpoint.deviceId(),
                        error
                );
            }
        }

        private synchronized void close(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            if (wakeTask != null) {
                wakeTask.cancel(false);
                wakeTask = null;
            }
            CancellationException cancelled = new CancellationException(reason);
            setQueue.forEach(command -> command.result.completeExceptionally(cancelled));
            setQueue.clear();
            entries.values().forEach(PollEntry::close);
            entries.clear();
            failureLogs.clear();
            logger.info(
                    "SNMP device runtime closed device={} host={} port={} version={} generation={} reason={}",
                    endpoint.deviceId(),
                    endpoint.host(),
                    endpoint.port(),
                    endpoint.version(),
                    generation,
                    reason
            );
        }
    }

    private static final class PollEntry {
        private final PollKey key;
        private final LongSupplier nanoClock;
        private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        private long intervalMillis = 1_000L;
        private long nextDueNanos;
        private boolean inFlight;
        private boolean isolated;
        private RawBinding latestBinding;
        private Throwable latestFailure;

        private PollEntry(PollKey key, LongSupplier nanoClock) {
            this.key = key;
            this.nanoClock = nanoClock;
            this.nextDueNanos = nanoClock.getAsLong();
        }

        private Subscriber addSubscriber(
                long intervalMillis,
                Consumer<RawBinding> onValue,
                Consumer<Throwable> onFailure
        ) {
            Subscriber subscriber = new Subscriber(intervalMillis, onValue, onFailure);
            subscribers.add(subscriber);
            recomputeInterval();
            nextDueNanos = Math.min(nextDueNanos, nanoClock.getAsLong());
            return subscriber;
        }

        private void removeSubscriber(Subscriber subscriber) {
            subscribers.remove(subscriber);
            recomputeInterval();
        }

        private void recomputeInterval() {
            intervalMillis = subscribers.stream()
                    .filter(subscriber -> !subscriber.closed.get())
                    .mapToLong(subscriber -> subscriber.intervalMillis)
                    .min()
                    .orElse(1_000L);
        }

        private boolean isDue(long nowNanos) {
            return hasSubscribers() && !inFlight && nowNanos >= nextDueNanos;
        }

        private boolean hasSubscribers() {
            return subscribers.stream().anyMatch(subscriber -> !subscriber.closed.get());
        }

        private int subscriberCount() {
            return (int) subscribers.stream().filter(subscriber -> !subscriber.closed.get()).count();
        }

        private void publishValue(RawBinding binding, long nowNanos) {
            latestBinding = binding;
            latestFailure = null;
            subscribers.forEach(subscriber ->
                    subscriber.publishValue(binding, key, nowNanos, true));
        }

        private void publishFailure(Throwable failure, long nowNanos) {
            latestBinding = null;
            latestFailure = failure;
            subscribers.forEach(subscriber ->
                    subscriber.publishFailure(failure, key, nowNanos, true));
        }

        private void publishCachedDue(long nowNanos) {
            if (latestFailure != null) {
                subscribers.forEach(subscriber ->
                        subscriber.publishFailure(latestFailure, key, nowNanos, false));
            } else if (latestBinding != null) {
                subscribers.forEach(subscriber ->
                        subscriber.publishValue(latestBinding, key, nowNanos, false));
            }
        }

        private long nextDeliveryDueNanos() {
            if (latestBinding == null && latestFailure == null) {
                return Long.MAX_VALUE;
            }
            return subscribers.stream()
                    .mapToLong(Subscriber::nextDeliveryDueNanos)
                    .min()
                    .orElse(Long.MAX_VALUE);
        }

        private void close() {
            subscribers.forEach(Subscriber::close);
            subscribers.clear();
            inFlight = false;
            latestBinding = null;
            latestFailure = null;
        }
    }

    private static final class Subscriber {
        private final long intervalMillis;
        private final Consumer<RawBinding> onValue;
        private final Consumer<Throwable> onFailure;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean delivered;
        private long nextDeliveryNanos = Long.MAX_VALUE;

        private Subscriber(
                long intervalMillis,
                Consumer<RawBinding> onValue,
                Consumer<Throwable> onFailure
        ) {
            this.intervalMillis = intervalMillis;
            this.onValue = Objects.requireNonNull(onValue);
            this.onFailure = Objects.requireNonNull(onFailure);
        }

        private void publishValue(
                RawBinding binding,
                PollKey key,
                long nowNanos,
                boolean allowFirstDelivery
        ) {
            if (!reserveDelivery(nowNanos, allowFirstDelivery)) {
                return;
            }
            try {
                onValue.accept(binding);
            } catch (RuntimeException error) {
                SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
                logger.error(
                        "SNMP subscriber value callback failed device={} oid={} category={} message={}",
                        key.deviceId(),
                        key.oid().canonical(),
                        failure.category(),
                        failure.message(),
                        error
                );
            }
        }

        private void publishFailure(
                Throwable failure,
                PollKey key,
                long nowNanos,
                boolean allowFirstDelivery
        ) {
            if (!reserveDelivery(nowNanos, allowFirstDelivery)) {
                return;
            }
            try {
                onFailure.accept(failure);
            } catch (RuntimeException error) {
                SnmpFailureClassifier.Failure classified = SnmpFailureClassifier.classify(error);
                logger.error(
                        "SNMP subscriber failure callback failed device={} oid={} category={} message={}",
                        key.deviceId(),
                        key.oid().canonical(),
                        classified.category(),
                        classified.message(),
                        error
                );
            }
        }

        private boolean reserveDelivery(long nowNanos, boolean allowFirstDelivery) {
            if (closed.get()) {
                return false;
            }
            if (!delivered) {
                if (!allowFirstDelivery) {
                    return false;
                }
                delivered = true;
            } else if (nowNanos < nextDeliveryNanos) {
                return false;
            }
            nextDeliveryNanos = saturatedAdd(
                    nowNanos,
                    TimeUnit.MILLISECONDS.toNanos(intervalMillis)
            );
            return true;
        }

        private long nextDeliveryDueNanos() {
            return closed.get() || !delivered ? Long.MAX_VALUE : nextDeliveryNanos;
        }

        private boolean close() {
            return closed.compareAndSet(false, true);
        }
    }

    private static final class SetCommand {
        private final ResolvedAddress address;
        private final Variable value;
        private final long deadlineNanos;
        private final CompletableFuture<SetResult> result = new CompletableFuture<>();

        private SetCommand(ResolvedAddress address, Variable value, long deadlineNanos) {
            this.address = Objects.requireNonNull(address);
            this.value = Objects.requireNonNull(value);
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static SnmpProtocolException protocol(BatchResult result) {
        return new SnmpProtocolException(
                result.errorStatus(),
                result.errorIndex(),
                result.errorText()
        );
    }

    private static boolean isExceptionSyntax(int syntax) {
        return syntax == SMIConstants.EXCEPTION_NO_SUCH_OBJECT
                || syntax == SMIConstants.EXCEPTION_NO_SUCH_INSTANCE
                || syntax == SMIConstants.EXCEPTION_END_OF_MIB_VIEW;
    }

    public static boolean isCircuitFailure(Throwable failure) {
        return SnmpFailureClassifier.classify(failure).circuitFailure();
    }

    public static Throwable unwrap(Throwable failure) {
        return SnmpFailureClassifier.unwrap(failure);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class FailureLogState {
        private long lastWarnNanos;
        private long suppressed;

        private FailureLogState(long lastWarnNanos, long suppressed) {
            this.lastWarnNanos = lastWarnNanos;
            this.suppressed = suppressed;
        }
    }
}

