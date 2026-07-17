package com.jw.ignition.common.api;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    public RouteHandler wrap(RouteHandler delegate) {
        return (request, response) -> {
            try {
                return delegate.handle(request, response);
            } catch (Exception e) {
                return error(request, response, e);
            }
        };
    }

    public static Object error(RequestContext request, HttpServletResponse response, Throwable error) {
        if (error instanceof ApiException apiException) {
            return response.isCommitted() ? null : ApiResponses.error(response, apiException);
        }

        String correlationId = UUID.randomUUID().toString();
        HttpServletRequest servletRequest = request.getRequest();
        LOG.error(
                "Unhandled API error. correlationId={} method={} path={} query={} actor={} remote={}",
                correlationId,
                servletRequest.getMethod(),
                servletRequest.getRequestURI(),
                servletRequest.getQueryString(),
                request.getActor(),
                servletRequest.getRemoteAddr(),
                error
        );

        if (response.isCommitted()) {
            return null;
        }
        return ApiResponses.internalError(response, correlationId);
    }
}
