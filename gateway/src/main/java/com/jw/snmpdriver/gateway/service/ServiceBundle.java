package com.jw.snmpdriver.gateway.service;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.jw.ignition.common.license.ModuleLicenseRuntime;
import com.jw.ignition.common.service.AbstractBundle;
import com.jw.snmpdriver.common.ModuleConstants;
import com.jw.snmpdriver.snmp.runtime.DnsResolver;
import com.jw.snmpdriver.snmp.runtime.SharedPollCoordinator;
import com.jw.snmpdriver.snmp.runtime.Snmp4jRequestClient;

public final class ServiceBundle extends AbstractBundle {
    private static final AtomicInteger POLL_THREAD_ID = new AtomicInteger();

    private final ConcurrentMap<String, Device> devices = new ConcurrentHashMap<>();

    private State state = State.NEW;
    private GatewayContext context;
    private ModuleLicenseRuntime license;
    private DnsResolver dnsResolver;
    private Snmp4jRequestClient requestClient;
    private ScheduledExecutorService scheduler;
    private SharedPollCoordinator runtime;

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
            requestClient = new Snmp4jRequestClient(dnsResolver);
            scheduler = Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "snmp-poll-" + POLL_THREAD_ID.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            });
            runtime = new SharedPollCoordinator(requestClient, scheduler);
            requestClient.start();
            runtime.start();
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

    public synchronized SharedPollCoordinator runtime() {
        return ensureStarted();
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
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (RuntimeException error) {
                logger.warn("Failed to stop SNMP coordinator cleanly", error);
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
        runtime = null;
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
