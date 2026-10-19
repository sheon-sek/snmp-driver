package com.jw.snmpdriver.gateway.device;

import java.util.function.BiConsumer;

import com.jw.snmpdriver.snmp.runtime.SnmpFailureClassifier;

/** Device status boundary; all transitions and their publication share one lock. */
final class DeviceStatusMachine {
    private final BiConsumer<String, String> onStatus;
    private boolean startupFailed;
    private boolean shuttingDown;
    private long generation;
    private String status = "Starting";

    DeviceStatusMachine(BiConsumer<String, String> onStatus) {
        this.onStatus = onStatus;
    }

    synchronized String status() {
        return status;
    }

    synchronized long beginGeneration() {
        return ++generation;
    }

    synchronized long currentGeneration() {
        return generation;
    }

    synchronized void shutdown() {
        shuttingDown = true;
    }

    synchronized boolean accepts(long callbackGeneration) {
        return !shuttingDown && !startupFailed && callbackGeneration == generation;
    }

    synchronized void activity(long callbackGeneration, int activeOidCount) {
        if (!accepts(callbackGeneration)) {
            return;
        }
        if (activeOidCount == 0) {
            setStatus("Idle");
        } else if ("Idle".equals(status) || "Starting".equals(status)) {
            setStatus("Connecting");
        }
    }

    synchronized void startupFailure(long callbackGeneration) {
        if (!accepts(callbackGeneration)) {
            return;
        }
        startupFailed = true;
        setStatus("Errored");
    }

    synchronized void running(long callbackGeneration, int activeOidCount) {
        if (!accepts(callbackGeneration)) {
            return;
        }
        setStatus(activeOidCount > 0 ? "Running" : "Idle");
    }

    synchronized void failure(long callbackGeneration, Throwable error, int activeOidCount) {
        if (!accepts(callbackGeneration)) {
            return;
        }
        SnmpFailureClassifier.Failure failure = SnmpFailureClassifier.classify(error);
        if ("license".equals(failure.category())) {
            setStatus("Unlicensed");
        } else if (failure.legalResponse()) {
            running(callbackGeneration, activeOidCount);
        } else if (failure.circuitFailure() || "resource".equals(failure.category())) {
            setStatus("Degraded");
        } else if (failure.unexpected()) {
            setStatus("Errored");
        }
    }

    private void setStatus(String nextStatus) {
        String previousStatus = status;
        status = nextStatus;
        onStatus.accept(previousStatus, nextStatus);
    }
}
