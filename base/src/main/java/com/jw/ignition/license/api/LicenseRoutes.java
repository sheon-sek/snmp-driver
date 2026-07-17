package com.jw.ignition.license.api;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.jw.ignition.common.api.ApiErrorHandler;
import com.jw.ignition.common.api.ApiException;
import com.jw.ignition.common.api.ApiResponses;
import com.jw.ignition.license.exception.LicenseUploadException;
import com.jw.ignition.license.model.LicenseStatusModel;
import com.jw.ignition.license.page.LicensePageRenderer;
import com.jw.ignition.license.service.ModuleLicenseService;
import com.jw.ignition.license.upload.LicenseUpload;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class LicenseRoutes {
    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=UTF-8";
    private static final String TEXT_HTML_UTF8 = "text/html; charset=UTF-8";

    private final ModuleLicenseService license;
    private final String moduleName;
    private final String machineRequestFileName;
    private final LicensePageRenderer pageRenderer = new LicensePageRenderer();

    public LicenseRoutes(ModuleLicenseService license, String moduleName, String mountAlias) {
        this.license = license;
        this.moduleName = moduleName == null || moduleName.isBlank() ? "Module" : moduleName.trim();
        this.machineRequestFileName = safeFilePrefix(mountAlias) + "-machine-information.txt";
    }

    // Render a simple HTML page for engineer to view license information + download machine info + upload license
    public Object page(RequestContext request, HttpServletResponse response) {
        try {
            byte[] content = pageRenderer.render(moduleName).getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(TEXT_HTML_UTF8);
            response.setHeader("Cache-Control", "no-store");
            response.setContentLength(content.length);
            response.getOutputStream().write(content);
            return null;
        } catch (Exception e) {
            return ApiErrorHandler.error(request, response, e);
        }
    }

    // api call to check current license status to this module
    public Object status(RequestContext request, HttpServletResponse response) {
        return ApiResponses.ok(license.currentStatus());
    }

    // Function to generate a file that consist of available computer information
    public Object machineRequest(RequestContext request, HttpServletResponse response) {
        try {
            byte[] content = license.machineRequestFile().getBytes(StandardCharsets.UTF_8);
            writeStream(response, machineRequestFileName, TEXT_PLAIN_UTF8, output -> output.write(content));
            return null;
        } catch (Exception e) {
            return ApiErrorHandler.error(request, response, e);
        }
    }

    // Function to upload license file through UI
    public Object upload(RequestContext request, HttpServletResponse response) {
        try {
            LicenseUpload upload = license.uploadParser().parse(request.getRequest());
            LicenseStatusModel status = license.upload(upload);
            if (!status.licensed) {
                throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "LICENSE_REJECTED", status.message, status);
            }
            return ApiResponses.ok(status);
        } catch (LicenseUploadException e) {
            return ApiErrorHandler.error(request, response, new ApiException(e.status(), e.code(), e.getMessage()));
        } catch (Exception e) {
            return ApiErrorHandler.error(request, response, e);
        }
    }

    // Function to revalidate current license
    public Object revalidate(RequestContext request, HttpServletResponse response) {
        try {
            LicenseStatusModel status = license.revalidate();
            return ApiResponses.ok(status);
        } catch (Exception e) {
            return ApiErrorHandler.error(request, response, e);
        }
    }

    @FunctionalInterface
    public interface StreamWriter {
        void write(OutputStream output) throws IOException;
    }

    public static void writeStream(HttpServletResponse response, String fileName, String contentType, StreamWriter writer) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        response.setHeader("Content-Disposition", attachment(fileName));
        writer.write(response.getOutputStream());
    }

    private static String attachment(String fileName) {
        String safeName = fileName == null || fileName.isBlank() ? "download" : fileName;
        safeName = safeName
                .replace('\\', '_')
                .replace('/', '_')
                .replace("\"", "")
                .replace("\r", "")
                .replace("\n", "");
        String asciiName = safeName.replaceAll("[^\\x20-\\x7E]", "_");
        String encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encoded;
    }

    private static String safeFilePrefix(String value) {
        String prefix = value == null || value.isBlank() ? "module" : value.trim();
        return prefix
            .replace('\\', '_')
            .replace('/', '_')
            .replace("\"", "")
            .replace("\r", "")
            .replace("\n", "");
    }
}
