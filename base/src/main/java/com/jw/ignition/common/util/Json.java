package com.jw.ignition.common.util;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class Json {
    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    private Json() {}

    public static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        try (Reader reader = request.getReader()) {
            return GSON.fromJson(reader, type);
        }
    }
    public static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(GSON.toJson(body));
    }
}
