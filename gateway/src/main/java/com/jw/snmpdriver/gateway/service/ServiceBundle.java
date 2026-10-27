package com.jw.snmpdriver.gateway.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignition.common.service.AbstractBundle;
import com.jw.ignition.license.service.MachineIdentifierMatcher;
import com.jw.ignition.license.service.MachineInfoService;
import com.jw.snmpdriver.common.ModuleConstants;
import com.jw.snmpdriver.snmp.runtime.CallbackExecutor;
import com.jw.snmpdriver.snmp.runtime.DnsResolver;
import com.jw.snmpdriver.snmp.runtime.LicenseGate;
import com.jw.snmpdriver.snmp.runtime.LicensedSnmpRequestClient;
import com.jw.snmpdriver.snmp.runtime.SharedPollCoordinator;
import com.jw.snmpdriver.snmp.runtime.Snmp4jRequestClient;
import com.jw.snmpdriver.snmp.runtime.SnmpRequestClient;
import com.jw.snmpdriver.snmp.trap.Snmp4jListenerBinder;
import com.jw.snmpdriver.snmp.trap.TrapEngineIdentity;
import com.jw.snmpdriver.snmp.trap.TrapListenerRegistry;
import org.snmp4j.mp.MPv3;

public final class ServiceBundle extends AbstractBundle {
    private static final AtomicInteger POLL_THREAD_ID = new AtomicInteger();
    private static final AtomicInteger RECORD_THREAD_ID = new AtomicInteger();
    /** Record Delivery threads shared by all Devices; one Device uses at most one at a time (ADR 0010). */
    private static final int RECORD_THREADS = 4;

    private final ConcurrentMap<String, Device> devices = new ConcurrentHashMap<>();

    private State state = State.NEW;
    private GatewayContext context;
    private ModuleLicenseRuntime license;
    private volatile LicenseGate licenseGate;
    private DnsResolver dnsResolver;
    private SnmpRequestClient requestClient;
    private ScheduledExecutorService scheduler;
    private SharedPollCoordinator runtime;
    private CallbackExecutor callbacks;
    private TrapListenerRegistry trapRegistry;
    private volatile ExecutorService recordExecutor;

    @Override
    protected synchronized void setupProcess(GatewayContext context) {
        this.context = Objects.requireNonNull(context);

        ModuleLicenseRuntime licenseRuntime = new ModuleLicenseRuntime(
                context,
                ModuleConstants.MODULE_ID,
                ModuleConstants.MODULE_NAME,
                ModuleConstants.MOUNT_ALIAS
        );
        license = licenseRuntime;
        licenseRuntime.setup();

        state = State.SET_UP;
    }

    @Override
    protected void startupProcess() {
        license().startup();
        start();
    }

    @Override
    protected void shutdownProcess() {
        RuntimeException failure = null;

        try {
            stop();
        } catch (RuntimeException error) {
            failure = error;
        }

        ModuleLicenseRuntime activeLicense = license;
        license = null;
        if (activeLicense != null) {
            try {
                activeLicense.shutdown();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    public synchronized void start() {
        ensureStarted();
    }

    public synchronized SharedPollCoordinator ensureStarted() {
        if (state == State.RUNNING) {
            return runtime;
        }
        if (state != State.SET_UP) {
            throw new IllegalStateException("SNMP services must be set up before start");
        }

        state = State.STARTING;
        try {
            dnsResolver = new DnsResolver();
            licenseGate = new LicenseGate(license());
            requestClient = new LicensedSnmpRequestClient(
                    new Snmp4jRequestClient(dnsResolver),
                    licenseGate
            );
            scheduler = Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "snmp-poll-" + POLL_THREAD_ID.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            });
            callbacks = new CallbackExecutor();
            recordExecutor = Executors.newFixedThreadPool(RECORD_THREADS, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "snmp-record-" + RECORD_THREAD_ID.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            });
            runtime = new SharedPollCoordinator(requestClient, scheduler, callbacks);
            requestClient.start();
            runtime.start();
            trapRegistry = TrapListenerRegistry.tryCreate(
                    () -> new TrapListenerRegistry(new Snmp4jListenerBinder(), scheduler, licenseGate::check,
                            loadTrapEngineIdentity()),
                    error -> logger.error("Failed to create Trap listener registry; Trap is unavailable", error)
            );
            state = State.RUNNING;
            logger.info("Shared SNMP runtime started");
            return runtime;
        } catch (IOException | RuntimeException error) {
            closeRuntimeParts();
            state = State.SET_UP;
            logger.error("Failed to start shared SNMP runtime", error);
            throw new IllegalStateException("Failed to start shared SNMP runtime", error);
        }
    }

    /** Once per registry: boots+1 is written here, before any Trap listener binds (plan §4.6, ADR 0009). */
    private TrapEngineIdentity loadTrapEngineIdentity() {
        try {
            Path file = context.getSystemManager().getModuleConfigLocalDir(ModuleConstants.MODULE_ID)
                    .resolve(TrapEngineIdentity.FILE_NAME);
            return TrapEngineIdentity.load(file,
                    () -> MachineIdentifierMatcher.hashesByKey(new MachineInfoService().collect().hashedMachineInfo),
                    MPv3::createLocalEngineID, Clock.systemUTC());
        } catch (RuntimeException error) {
            logger.warn("Failed to locate the Trap engine identity file; v3 Inform is disabled", error);
            return TrapEngineIdentity.unpersisted(MPv3.createLocalEngineID(),
                    "无法定位 Trap 本地引擎身份文件（" + error + "），v3 Inform 已停用；v1/v2c 接收不受影响。");
        }
    }

    public synchronized void stop() {
        if (state == State.STOPPED) {
            return;
        }
        try {
            closeRuntimeParts();
        } finally {
            devices.clear();
            context = null;
            state = State.STOPPED;
        }
    }

    public synchronized GatewayContext context() {
        requireRunning();
        return context;
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** The shared scheduler for light periodic work (Trap counter publishing). */
    public synchronized ScheduledExecutorService scheduler() {
        requireRunning();
        return scheduler;
    }

    /**
     * The pool that runs Record handler calls; null when the services are not running. Lock-free for the same
     * reason as {@link #licenseVerdict()}: the Trap listener worker reaches it.
     */
    public Executor recordExecutor() {
        return recordExecutor;
    }

    public synchronized SharedPollCoordinator runtime() {
        return ensureStarted();
    }

    /** Null when the registry could not be created; Trap then stays unavailable while polling runs. */
    public synchronized Optional<TrapListenerRegistry> trapRegistry() {
        return Optional.ofNullable(trapRegistry);
    }

    /**
     * Not licensed when the services are not running. Deliberately lock-free: Trap diagnostics call it
     * while stop() may hold this bundle's lock and wait for a transport thread.
     */
    public LicenseGate.Verdict licenseVerdict() {
        LicenseGate gate = licenseGate;
        return gate == null ? LicenseGate.Verdict.NOT_AVAILABLE : gate.check();
    }

    public ModuleLicenseRuntime license() {
        return Objects.requireNonNull(license, "ServiceBundle is not set up");
    }

    public void registerDevice(String deviceName, Device device) {
        devices.put(Objects.requireNonNull(deviceName), Objects.requireNonNull(device));
    }

    public void unregisterDevice(String deviceName, Device device) {
        devices.remove(deviceName, device);
    }

    public Optional<String> deviceStatus(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(devices.get(deviceName)).map(Device::getStatus);
    }

    private void closeRuntimeParts() {
        if (trapRegistry != null) {
            try {
                trapRegistry.closeAll();
            } catch (RuntimeException error) {
                logger.warn("Failed to close Trap listeners cleanly", error);
            }
        }
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (RuntimeException error) {
                logger.warn("Failed to stop SNMP coordinator cleanly", error);
            }
        }
        if (callbacks != null) {
            try {
                callbacks.shutdown();
                if (!callbacks.awaitTermination(2, TimeUnit.SECONDS)) {
                    callbacks.shutdownNow();
                }
            } catch (InterruptedException error) {
                callbacks.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                logger.warn("Failed to stop SNMP callbacks cleanly", error);
            }
        }
        ExecutorService records = recordExecutor;
        recordExecutor = null;
        if (records != null) {
            try {
                // no interrupt: a handler call already running finishes on its own
                records.shutdown();
            } catch (RuntimeException error) {
                logger.warn("Failed to stop Record Delivery pool cleanly", error);
            }
        }
        if (requestClient != null) {
            try {
                requestClient.close();
            } catch (RuntimeException error) {
                logger.warn("Failed to close SNMP request client cleanly", error);
            }
        }
        if (dnsResolver != null) {
            try {
                dnsResolver.close();
            } catch (RuntimeException error) {
                logger.warn("Failed to close SNMP DNS resolver cleanly", error);
            }
        }
        if (scheduler != null) {
            try {
                scheduler.shutdownNow();
            } catch (RuntimeException error) {
                logger.warn("Failed to stop SNMP scheduler cleanly", error);
            }
        }
        trapRegistry = null;
        runtime = null;
        callbacks = null;
        requestClient = null;
        dnsResolver = null;
        scheduler = null;
    }

    private void requireRunning() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("SNMP services are not running");
        }
    }

    private enum State {
        NEW,
        SET_UP,
        STARTING,
        RUNNING,
        STOPPED
    }
}
