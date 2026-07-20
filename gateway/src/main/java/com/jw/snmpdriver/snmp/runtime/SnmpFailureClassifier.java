package com.jw.snmpdriver.snmp.runtime;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;

/**
 * Converts runtime failures into one stable, secret-safe vocabulary used by
 * OPC quality, Diagnostics, Device lifecycle, circuit breaking, and logs.
 */
public final class SnmpFailureClassifier {
    private static final int MAX_MESSAGE_LENGTH = 1_024;

    private SnmpFailureClassifier() {
    }

    public static Failure classify(Throwable error) {
        Throwable root = unwrap(error);
        if (root == null) {
            return new Failure(
                    "runtime",
                    "SNMP operation failed without an exception",
                    StatusCodes.Bad_UnexpectedError,
                    false,
                    false,
                    true
            );
        }
        if (root instanceof SnmpValueException valueError) {
            return failure(
                    valueError.category(),
                    root,
                    valueError.statusCode(),
                    false,
                    false,
                    false
            );
        }
        if (root instanceof SnmpConfigurationException) {
            return failure(
                    "configuration",
                    root,
                    StatusCodes.Bad_ConfigurationError,
                    false,
                    false,
                    true
            );
        }
        if (root instanceof SnmpQueueTimeoutException) {
            return failure("queue-timeout", root, StatusCodes.Bad_Timeout, false, false, false);
        }
        if (root instanceof SnmpDnsTimeoutException) {
            return failure("dns", root, StatusCodes.Bad_Timeout, true, false, false);
        }
        if (root instanceof SnmpNoResponseException) {
            return failure("no-response", root, StatusCodes.Bad_Timeout, true, false, false);
        }
        if (root instanceof TimeoutException) {
            return failure("timeout", root, StatusCodes.Bad_Timeout, true, false, false);
        }
        if (root instanceof UnknownHostException) {
            return failure("dns", root, StatusCodes.Bad_NoCommunication, true, false, false);
        }
        if (root instanceof SnmpSecurityReportException) {
            return failure(
                    "security",
                    root,
                    StatusCodes.Bad_SecurityChecksFailed,
                    true,
                    false,
                    false
            );
        }
        if (root instanceof SnmpReportException reportError) {
            return failure(
                    reportError.category(),
                    root,
                    reportError.statusCode(),
                    reportError.circuitFailure(),
                    reportError.legalResponse(),
                    false
            );
        }
        if (root instanceof SecurityException) {
            return failure(
                    "security-configuration",
                    root,
                    StatusCodes.Bad_SecurityChecksFailed,
                    false,
                    false,
                    true
            );
        }
        if (root instanceof SnmpProtocolException protocolError) {
            return failure(
                    "protocol",
                    root,
                    protocolStatus(protocolError.errorStatus()),
                    false,
                    true,
                    false
            );
        }
        if (root instanceof MessageException messageError) {
            int status = messageError.getSnmp4jErrorStatus();
            if (isLocalSecurityConfigurationFailure(status)) {
                return new Failure(
                        "security-configuration",
                        messageExceptionMessage(messageError),
                        StatusCodes.Bad_SecurityChecksFailed,
                        false,
                        false,
                        true
                );
            }
            if (isLocalMessageRuntimeFailure(status)) {
                return new Failure(
                        "message-processing",
                        messageExceptionMessage(messageError),
                        StatusCodes.Bad_UnexpectedError,
                        false,
                        false,
                        true
                );
            }
            boolean security = isSecurityMessageError(status);
            return new Failure(
                    security ? "security" : "message-processing",
                    messageExceptionMessage(messageError),
                    security ? StatusCodes.Bad_SecurityChecksFailed : StatusCodes.Bad_NoCommunication,
                    true,
                    false,
                    false
            );
        }
        if (root instanceof IOException) {
            return failure("transport", root, StatusCodes.Bad_NoCommunication, true, false, false);
        }
        if (root instanceof CancellationException || root instanceof InterruptedException) {
            return failure("shutdown", root, StatusCodes.Bad_Shutdown, false, false, false);
        }
        if (root instanceof RejectedExecutionException) {
            return failure("resource", root, StatusCodes.Bad_ResourceUnavailable, false, false, false);
        }
        if (root instanceof IllegalArgumentException) {
            return failure("configuration", root, StatusCodes.Bad_ConfigurationError, false, false, true);
        }
        return failure("runtime", root, StatusCodes.Bad_UnexpectedError, false, false, true);
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static boolean safeToLogStack(Throwable error) {
        Throwable root = unwrap(error);
        return !(root instanceof MessageException)
                && !(root instanceof SecurityException)
                && !(root instanceof IllegalArgumentException);
    }

    private static Failure failure(
            String category,
            Throwable error,
            long statusCode,
            boolean circuitFailure,
            boolean legalResponse,
            boolean unexpected
    ) {
        return new Failure(
                category,
                safeMessage(error, defaultMessage(category)),
                statusCode,
                circuitFailure,
                legalResponse,
                unexpected
        );
    }

    private static String defaultMessage(String category) {
        return switch (category) {
            case "value", "decode", "encode", "type", "interval", "item-path" ->
                    "SNMP value or Item Path processing failed";
            case "queue-timeout" -> "SNMP SET expired in the local device queue";
            case "dns" -> "SNMP destination DNS resolution failed";
            case "no-response" -> "SNMP request completed without a response";
            case "timeout", "write-wait" -> "SNMP operation exceeded its deadline";
            case "security" -> "SNMP security validation failed";
            case "security-configuration" -> "SNMP security configuration failed";
            case "transport" -> "SNMP transport I/O failed";
            case "shutdown" -> "SNMP operation was cancelled by shutdown or generation change";
            case "resource" -> "SNMP runtime resource is unavailable";
            case "configuration" -> "SNMP configuration is invalid";
            case "message-processing" -> "SNMP message processing failed";
            default -> "Unexpected SNMP runtime failure";
        };
    }

    private static long protocolStatus(int errorStatus) {
        return switch (errorStatus) {
            case PDU.noSuchName, PDU.noCreation, PDU.inconsistentName ->
                    StatusCodes.Bad_NodeIdUnknown;
            case PDU.readOnly, PDU.notWritable -> StatusCodes.Bad_NotWritable;
            case PDU.noAccess, PDU.authorizationError -> StatusCodes.Bad_UserAccessDenied;
            case PDU.wrongType -> StatusCodes.Bad_TypeMismatch;
            case PDU.wrongEncoding -> StatusCodes.Bad_EncodingError;
            case PDU.badValue, PDU.wrongLength, PDU.wrongValue, PDU.inconsistentValue ->
                    StatusCodes.Bad_OutOfRange;
            case PDU.tooBig -> StatusCodes.Bad_EncodingLimitsExceeded;
            case PDU.resourceUnavailable -> StatusCodes.Bad_ResourceUnavailable;
            default -> StatusCodes.Bad_UnexpectedError;
        };
    }

    private static boolean isSecurityMessageError(int status) {
        if (status >= SnmpConstants.SNMPv3_USM_ERROR
                && status <= SnmpConstants.SNMPv3_USM_SECURITY_NAME_TOO_LONG) {
            return true;
        }
        return status == SnmpConstants.SNMP_MP_UNSUPPORTED_SECURITY_MODEL
                || status == SnmpConstants.SNMP_MP_NOT_IN_TIME_WINDOW
                || status == SnmpConstants.SNMP_MP_INVALID_ENGINEID
                || status == SnmpConstants.SNMP_MP_WRONG_USER_NAME
                || status == SnmpConstants.SNMP_MP_USM_ERROR;
    }

    private static boolean isLocalSecurityConfigurationFailure(int status) {
        return status == SnmpConstants.SNMPv3_USM_UNSUPPORTED_AUTHPROTOCOL
                || status == SnmpConstants.SNMPv3_USM_UNSUPPORTED_PRIVPROTOCOL
                || status == SnmpConstants.SNMPv3_USM_ENCRYPTION_ERROR;
    }

    private static boolean isLocalMessageRuntimeFailure(int status) {
        return status == SnmpConstants.SNMP_MP_NOT_INITIALIZED
                || status == SnmpConstants.SNMP_MP_BUILD_ERROR;
    }

    private static String messageExceptionMessage(MessageException error) {
        int status = error.getSnmp4jErrorStatus();
        String reason;
        if (status >= SnmpConstants.SNMPv3_USM_ERROR
                && status <= SnmpConstants.SNMPv3_USM_SECURITY_NAME_TOO_LONG) {
            reason = SnmpConstants.usmErrorMessage(status);
        } else if (status <= SnmpConstants.SNMP_MP_ERROR
                && status >= SnmpConstants.SNMP_MP_REPORT_SENT) {
            reason = SnmpConstants.mpErrorMessage(status);
        } else {
            reason = "unclassified SNMP4J message error";
        }
        return sanitize("SNMP message processing failed status=" + status + " reason=" + reason);
    }

    private static String safeMessage(Throwable error, String fallback) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? fallback : sanitize(message);
    }

    private static String sanitize(String message) {
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= MAX_MESSAGE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    public record Failure(
            String category,
            String message,
            long statusCode,
            boolean circuitFailure,
            boolean legalResponse,
            boolean unexpected
    ) {
        public String diagnosticMessage() {
            return category + ": " + message;
        }
    }
}

