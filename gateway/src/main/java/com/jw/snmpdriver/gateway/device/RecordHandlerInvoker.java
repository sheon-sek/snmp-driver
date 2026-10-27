package com.jw.snmpdriver.gateway.device;

/**
 * Calls the engineer's Record handler: a function in a project's Script Library (ADR 0010). The driver never
 * parses the Record; it only learns whether the call returned, raised, or could not be made.
 */
@FunctionalInterface
public interface RecordHandlerInvoker {

    enum Outcome {
        /** The function returned normally. */
        SUCCEEDED,
        /** The function raised; {@code detail} carries the exception summary. */
        HANDLER_FAILED,
        /** The project or the function could not be resolved; {@code detail} names which. */
        NOT_FOUND
    }

    record Result(Outcome outcome, String detail) {
        public static final Result SUCCESS = new Result(Outcome.SUCCEEDED, "");

        public static Result handlerFailed(String detail) {
            return new Result(Outcome.HANDLER_FAILED, detail == null ? "" : detail);
        }

        public static Result notFound(String detail) {
            return new Result(Outcome.NOT_FOUND, detail == null ? "" : detail);
        }
    }

    /**
     * Calls {@code function} (a dotted Script Library path such as {@code snmp.handlers.onNotification}) of
     * {@code project} once with {@code record}, the Notification Record JSON. Resolves both on every call, so a
     * saved Script Library takes effect with the next Record.
     */
    Result invoke(String project, String function, String record);
}
