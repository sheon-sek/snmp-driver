package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** Record Delivery (ADR 0010, #48) through a real SNMPDevice and Trap listener, with a fake handler. */
class SNMPDeviceRecordDeliveryTest {
    private static final String FUNCTION = "snmp.handlers.onNotification";
    private static final Pattern SEQUENCE = Pattern.compile("\"sequence\":(\\d+),");

    private SNMPDeviceFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void everyAcceptedNotificationCallsTheHandlerOnceInArrivalOrderOneAtATime() throws Exception {
        FakeHandler handler = new FakeHandler();
        handler.holdFirstCall();
        fixture = new SNMPDeviceFixture(FUNCTION, handler);
        await("Listening", () -> "Listening".equals(fixture.value("Status")));

        for (int i = 1; i <= 10; i++) {
            sendV2cTrap(fixture.trapPort, i);
        }
        // the first call is still blocked, yet every Notification is received and published
        await("Sequence 10", () -> fixture.value("Last/Json") instanceof String json
                && json.startsWith("{\"sequence\":10,"));
        assertTrue(handler.calls.size() <= 1, "handler calls must not hold up receiving");
        await("all Records outstanding including in-flight", () -> Unsigned.ulong(10).equals(fixture.value("RecordQueueDepth")));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordDeliveredCount"));
        handler.release();

        await("10 handler calls", () -> handler.calls.size() == 10);
        List<Long> sequences = new ArrayList<>();
        for (Call call : handler.calls) {
            assertEquals("recordProject", call.project());
            assertEquals(FUNCTION, call.function());
            assertTrue(call.record().startsWith("{\"device\":\"probe\",\"sequence\":"), call.record());
            assertTrue(call.thread().startsWith("snmp-record-"), call.thread());
            sequences.add(sequenceOf(call.record()));
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), sequences);
        assertEquals(1, handler.maxInFlight.get(), "one call at a time per Device");
        // the Record is Last/Json plus the device field
        assertEquals("{\"device\":\"probe\"," + ((String) fixture.value("Last/Json")).substring(1),
                handler.calls.get(9).record());
        await("RecordDeliveredCount 10",
                () -> Unsigned.ulong(10).equals(fixture.value("RecordDeliveredCount")));
        assertEquals(Unsigned.ulong(10), fixture.value("AcceptedCount"));
    }

    @Test
    void notificationMatchingTwoDevicesCallsEachDevicesHandlerOnce() throws Exception {
        FakeHandler first = new FakeHandler();
        FakeHandler second = new FakeHandler();
        fixture = new SNMPDeviceFixture(FUNCTION, first);
        SNMPDevice other = fixture.addDevice("second", FUNCTION, second);
        await("Listening", () -> "Listening".equals(fixture.value("Status"))
                && "Listening".equals(valueOf(other, "Status")));

        sendV2cTrap(fixture.trapPort, 7);

        await("both handlers called", () -> first.calls.size() == 1 && second.calls.size() == 1);
        assertTrue(first.calls.get(0).record().startsWith("{\"device\":\"probe\",\"sequence\":1,"));
        assertTrue(second.calls.get(0).record().startsWith("{\"device\":\"second\",\"sequence\":1,"));
        await("RecordDeliveredCount on both", () -> Unsigned.ulong(1).equals(fixture.value("RecordDeliveredCount"))
                && Unsigned.ulong(1).equals(valueOf(other, "RecordDeliveredCount")));
        assertEquals(1, first.calls.size());
        assertEquals(1, second.calls.size());
    }

    @Test
    void deviceWithoutHandlerFunctionProducesNoRecordsAndCountsNothing() throws Exception {
        FakeHandler unused = new FakeHandler();
        FakeHandler control = new FakeHandler();
        fixture = new SNMPDeviceFixture("", unused);
        // a configured Device on the same port shows when the Notification has been fully handled
        SNMPDevice configured = fixture.addDevice("configured", FUNCTION, control);
        await("Listening", () -> "Listening".equals(fixture.value("Status"))
                && "Listening".equals(valueOf(configured, "Status")));

        sendV2cTrap(fixture.trapPort, 1);

        await("control handler called", () -> control.calls.size() == 1);
        await("AcceptedCount 1", () -> Unsigned.ulong(1).equals(fixture.value("AcceptedCount")));
        assertTrue(((String) fixture.value("Last/Json")).startsWith("{\"sequence\":1,"));
        assertEquals(0, unused.calls.size());
        assertEquals(Unsigned.ulong(0), fixture.value("RecordHandlerErrorCount"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordDroppedCount"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordQueueDepth"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordDeliveredCount"));
    }

    @Test
    void fullQueueDropsNewlyArrivedRecordsAndKeepsTheQueuedOnes() throws Exception {
        FakeHandler handler = new FakeHandler();
        handler.holdFirstCall();
        fixture = new SNMPDeviceFixture(FUNCTION, handler, 3);
        await("Listening", () -> "Listening".equals(fixture.value("Status")));

        sendV2cTrap(fixture.trapPort, 1);
        handler.awaitFirstCallStarted();
        for (int i = 2; i <= 10; i++) {
            sendV2cTrap(fixture.trapPort, i);
        }
        // Record 1 is in the handler, 2-4 fill the queue, 5-10 find it full
        await("6 dropped", () -> Unsigned.ulong(6).equals(fixture.value("RecordDroppedCount")));
        assertEquals(Unsigned.ulong(10), fixture.value("AcceptedCount"));
        assertEquals(Unsigned.ulong(4), fixture.value("RecordQueueDepth"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordDeliveredCount"));
        assertTrue(((String) fixture.value("LastError")).contains("Record"), (String) fixture.value("LastError"));
        assertReconciled();

        handler.release();
        await("4 delivered", () -> Unsigned.ulong(4).equals(fixture.value("RecordDeliveredCount"))
                && Unsigned.ulong(0).equals(fixture.value("RecordQueueDepth")));
        assertEquals(List.of(1L, 2L, 3L, 4L), handler.sequences());
        assertEquals(Unsigned.ulong(6), fixture.value("RecordDroppedCount"));
        assertReconciled();
    }

    @Test
    void stoppingTheDeviceCountsQueuedRecordsAsDroppedAndLetsTheRunningCallFinish() throws Exception {
        FakeHandler handler = new FakeHandler();
        handler.holdFirstCall();
        fixture = new SNMPDeviceFixture(FUNCTION, handler);
        await("Listening", () -> "Listening".equals(fixture.value("Status")));

        sendV2cTrap(fixture.trapPort, 1);
        handler.awaitFirstCallStarted();
        for (int i = 2; i <= 5; i++) {
            sendV2cTrap(fixture.trapPort, i);
        }
        await("AcceptedCount 5", () -> Unsigned.ulong(5).equals(fixture.value("AcceptedCount")));

        fixture.stopDevice();
        // the counters are published once more on stop
        assertEquals(Unsigned.ulong(4), fixture.value("RecordDroppedCount"));
        handler.release();
        await("running call finished", () -> handler.calls.size() == 1);
        Thread.sleep(200);
        assertEquals(List.of(1L), handler.sequences(), "queued Records are never delivered after stop");
    }

    private void assertReconciled() {
        long accepted = ((Number) fixture.value("AcceptedCount")).longValue();
        long delivered = ((Number) fixture.value("RecordDeliveredCount")).longValue();
        long errors = ((Number) fixture.value("RecordHandlerErrorCount")).longValue();
        long dropped = ((Number) fixture.value("RecordDroppedCount")).longValue();
        long depth = ((Number) fixture.value("RecordQueueDepth")).longValue();
        assertEquals(accepted, delivered + errors + dropped + depth,
                "AcceptedCount = Delivered + HandlerError + Dropped + QueueDepth");
    }

    @Test
    void handlerExceptionCountsAnErrorWithoutRetryAndNextRecordStillRuns() throws Exception {
        List<Long> calls = new CopyOnWriteArrayList<>();
        fixture = new SNMPDeviceFixture(FUNCTION, (project, function, record) -> {
            long sequence = sequenceOf(record);
            calls.add(sequence);
            if (sequence == 1) throw new IllegalStateException("ST_HANDLER_BROKEN");
            return RecordHandlerInvoker.Result.SUCCESS;
        });
        await("Listening", () -> "Listening".equals(fixture.value("Status")));
        sendV2cTrap(fixture.trapPort, 1);
        await("handler error", () -> Unsigned.ulong(1).equals(fixture.value("RecordHandlerErrorCount")));
        await("error shown", () -> ((String) fixture.value("LastError")).contains("ST_HANDLER_BROKEN"));
        sendV2cTrap(fixture.trapPort, 2);
        await("next delivery", () -> Unsigned.ulong(1).equals(fixture.value("RecordDeliveredCount")));
        assertEquals(List.of(1L, 2L), calls);
        assertEquals(Unsigned.ulong(2), fixture.value("AcceptedCount"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordDroppedCount"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordQueueDepth"));
        // a successful delivery clears the Record error from Traps/LastError
        await("error cleared", () -> "".equals(fixture.value("LastError")));
    }

    @Test
    void scriptErrorsAreCountedAndLoggedWithSequenceAndDoNotStopLaterRecords() throws Exception {
        java.io.PrintStream previous = System.err;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
            fixture = new SNMPDeviceFixture(FUNCTION, (project, function, record) -> {
                long sequence = sequenceOf(record);
                if (sequence == 1) throw new StackOverflowError("ST_RECURSION");
                if (sequence == 2) throw new AssertionError("ST_ASSERTION");
                return RecordHandlerInvoker.Result.SUCCESS;
            });
            await("Listening", () -> "Listening".equals(fixture.value("Status")));
            for (int i = 1; i <= 3; i++) sendV2cTrap(fixture.trapPort, i);
            await("two handler errors", () -> Unsigned.ulong(2).equals(fixture.value("RecordHandlerErrorCount")));
            await("later record delivered", () -> Unsigned.ulong(1).equals(fixture.value("RecordDeliveredCount")));
            assertEquals(Unsigned.ulong(3), fixture.value("AcceptedCount"));
            assertEquals(Unsigned.ulong(0), fixture.value("RecordDroppedCount"));
            assertEquals(Unsigned.ulong(0), fixture.value("RecordQueueDepth"));
            String log = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(log.contains("device=probe") && log.contains("sequence=1") && log.contains("ST_RECURSION"), log);
            assertTrue(log.contains("sequence=2") && log.contains("ST_ASSERTION"), log);
        } finally {
            System.setErr(previous);
        }
    }

    @Test
    void slowCallWarnsOnceAfterReturningAndStillDeliversWithoutInterruption() throws Exception {
        java.io.PrintStream previous = System.err;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
            fixture = new SNMPDeviceFixture(FUNCTION, (project, function, record) -> {
                if (sequenceOf(record) == 1) {
                    try {
                        Thread.sleep(5100);
                    } catch (InterruptedException error) {
                        throw new AssertionError("handler must not be interrupted", error);
                    }
                }
                return RecordHandlerInvoker.Result.SUCCESS;
            });
            await("Listening", () -> "Listening".equals(fixture.value("Status")));
            sendV2cTrap(fixture.trapPort, 1);
            sendV2cTrap(fixture.trapPort, 2);
            await("two calls delivered", () -> Unsigned.ulong(2).equals(fixture.value("RecordDeliveredCount")));
            String log = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(1, log.split("Slow record handler call", -1).length - 1, log);
            assertTrue(log.contains("device=probe") && log.contains("sequence=1")
                    && log.contains("project=recordProject") && log.contains("elapsedMillis="), log);
            assertEquals(Unsigned.ulong(0), fixture.value("RecordHandlerErrorCount"));
        } finally {
            System.setErr(previous);
        }
    }

    @Test
    void unavailableHandlerCountsDroppedAndReportsProjectAndFunctionThenRecovers() throws Exception {
        List<Long> calls = new CopyOnWriteArrayList<>();
        fixture = new SNMPDeviceFixture(FUNCTION, (project, function, record) -> {
            long sequence = sequenceOf(record);
            calls.add(sequence);
            return switch ((int) sequence) {
                case 1 -> RecordHandlerInvoker.Result.notFound("Project 'recordProject' is disabled");
                case 2 -> RecordHandlerInvoker.Result.notFound("Function '" + function + "' not found");
                case 3 -> RecordHandlerInvoker.Result.handlerFailed("ST_RETURNED_HANDLER_ERROR");
                default -> RecordHandlerInvoker.Result.SUCCESS;
            };
        });
        await("Listening", () -> "Listening".equals(fixture.value("Status")));
        sendV2cTrap(fixture.trapPort, 1);
        await("first drop", () -> Unsigned.ulong(1).equals(fixture.value("RecordDroppedCount")));
        await("disabled project LastError", () -> ((String) fixture.value("LastError"))
                .contains("recordProject' is disabled"));
        sendV2cTrap(fixture.trapPort, 2);
        await("second drop", () -> Unsigned.ulong(2).equals(fixture.value("RecordDroppedCount")));
        await("missing function LastError", () -> ((String) fixture.value("LastError"))
                .contains("Function '" + FUNCTION + "' not found"));
        sendV2cTrap(fixture.trapPort, 3);
        sendV2cTrap(fixture.trapPort, 4);
        await("recovery", () -> Unsigned.ulong(1).equals(fixture.value("RecordDeliveredCount")));
        assertEquals(List.of(1L, 2L, 3L, 4L), calls);
        assertEquals(Unsigned.ulong(4), fixture.value("AcceptedCount"));
        assertEquals(Unsigned.ulong(1), fixture.value("RecordHandlerErrorCount"));
        assertEquals(Unsigned.ulong(2), fixture.value("RecordDroppedCount"));
        assertEquals(Unsigned.ulong(0), fixture.value("RecordQueueDepth"));
    }

    private record Call(String project, String function, String record, String thread) {
    }

    /** Records every call; can hold the first call until released, and tracks concurrent calls. */
    private static final class FakeHandler implements RecordHandlerInvoker {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final CountDownLatch firstCallStarted = new CountDownLatch(1);
        private volatile CountDownLatch gate;

        void awaitFirstCallStarted() throws InterruptedException {
            assertTrue(firstCallStarted.await(15, TimeUnit.SECONDS), "handler was never called");
        }

        List<Long> sequences() {
            List<Long> sequences = new ArrayList<>();
            calls.forEach(call -> sequences.add(sequenceOf(call.record())));
            return sequences;
        }

        void holdFirstCall() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        @Override
        public Result invoke(String project, String function, String record) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            firstCallStarted.countDown();
            try {
                CountDownLatch current = gate;
                if (current != null && !current.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("first call was never released");
                }
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            calls.add(new Call(project, function, record, Thread.currentThread().getName()));
            return Result.SUCCESS;
        }
    }

    private static Object valueOf(SNMPDevice device, String trapsPath) {
        return SNMPDeviceFixture.read(device, trapsPath).getValue().getValue();
    }

    private static long sequenceOf(String record) {
        Matcher matcher = SEQUENCE.matcher(record);
        assertTrue(matcher.find(), record);
        return Long.parseLong(matcher.group(1));
    }

    private static void sendV2cTrap(int port, int value) throws Exception {
        PDU pdu = new PDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(900)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.3"), new Integer32(value)));
        try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();
            CommunityTarget<org.snmp4j.smi.Address> target = new CommunityTarget<>();
            target.setAddress(GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setCommunity(new OctetString("public"));
            target.setVersion(SnmpConstants.version2c);
            snmp.send(pdu, target);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }
}
