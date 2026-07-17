package com.jw.ignition.common.api;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonNull;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.jw.ignition.common.util.Json;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;

public final class ApiResponses {
    private ApiResponses() {}

    public static JsonObject ok(Object data) {
        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.add("data", toJsonElement(data));
        return body;
    }
    public static JsonObject ok(Object data, Object meta) {
        JsonObject body = ok(data);
        if (meta != null) {
            body.add("meta", toJsonElement(meta));
        }
        return body;
    }

    public static JsonObject error(HttpServletResponse response, int status, String code, String message) {
        return error(response, status, code, message, null);
    }
    public static JsonObject error(HttpServletResponse response, ApiException error) {
        return error(
                response,
                error.status(),
                error.code(),
                safeMessage(error.getMessage(), "Request failed."),
                error.details()
        );
    }
    public static JsonObject internalError(HttpServletResponse response, String correlationId) {
        return error(
                response,
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error.",
                null,
                correlationId
        );
    }
    public static JsonObject error(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            Object details
    ) {
        return error(response, status, code, message, details, null);
    }
    public static JsonObject error(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            Object details,
            String correlationId
    ){
        response.setStatus(status);

        JsonObject error = new JsonObject();

        error.addProperty("code", code);
        error.addProperty("message", message);
        if (details != null) {
            error.add("details", toJsonElement(details));
        }

        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        body.addProperty("success", false);
        body.add("error", error);
        body.addProperty("timestamp", Instant.now().toString());
        if (correlationId != null && !correlationId.isBlank()) {
            body.addProperty("correlationId", correlationId);
        }
        return body;
    }

    public static JsonObject notImplemented(HttpServletResponse response, String routeName) {
        return error(
                response,
                HttpServletResponse.SC_NOT_IMPLEMENTED,
                "NOT_IMPLEMENTED",
                routeName + " is registered but not implemented yet."
        );
    }

    public static JsonObject unauthenticated(HttpServletResponse response, String message) {
        return error(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHENTICATED",
                safeMessage(message, "Authentication required.")
        );
    }
    public static JsonObject unauthenticated(HttpServletResponse response) {
        return unauthenticated(response, "");
    }

    public static JsonObject unauthorized(HttpServletResponse response, String message) {
        return error(
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "UNAUTHORIZED",
                safeMessage(message, "Access denied.")
        );
    }
    public static JsonObject unauthorized(HttpServletResponse response) {
        return unauthorized(response, "");
    }

    // ------------------------------------------------------------------------------ //

    private static JsonElement toJsonElement(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        return Json.GSON.toJsonTree(value);
    }

    private static String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
