package com.jw.ignition.common.license;

import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jw.ignition.common.api.ApiException;
import com.jw.ignition.common.service.AbstractRuntimeService;
import com.jw.ignition.license.exception.LicenseAccessException;
import com.jw.ignition.license.exception.LicenseFeatureAccessException;
import com.jw.ignition.license.model.LicenseStatusModel;
import com.jw.ignition.license.service.ModuleLicenseService;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ModuleLicenseRuntime extends AbstractRuntimeService {
    private static final long MONITOR_INTERVAL_SECONDS = 5L;

    private final GatewayContext context;
    private final String moduleId;
    private final String moduleName;
    private final String mountAlias;

    private ModuleLicenseService license;
    private ScheduledExecutorService monitor;

    public ModuleLicenseRuntime(
            GatewayContext context,
            String moduleId,
            String moduleName,
            String mountAlias
    ) {
        this.context = Objects.requireNonNull(context, "Gateway context is required.");
        this.moduleId = requireText(moduleId, "Module id is required.");
        this.moduleName = requireText(moduleName, "Module name is required.");
        this.mountAlias = requireText(mountAlias, "Module mount alias is required.");
    }

    @Override
    protected void setupProcess() {
        license = new ModuleLicenseService(context, moduleId, mountAlias);
    }

    @Override
    protected void startupProcess() {
        service().revalidate("startup");

        monitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, mountAlias + "-license-monitor");
            thread.setDaemon(true);
            return thread;
        });
        monitor.scheduleWithFixedDelay(
                this::monitorLicense,
                MONITOR_INTERVAL_SECONDS,
                MONITOR_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Override
    protected void shutdownProcess() {
        ScheduledExecutorService activeMonitor = monitor;
        monitor = null;
        if (activeMonitor != null) {
            activeMonitor.shutdownNow();
        }
        license = null;
    }

    public ModuleLicenseService service() {
        return Objects.requireNonNull(license, "ModuleLicenseRuntime is not set up");
    }

    public LicenseStatusModel refresh() {
        ModuleLicenseService activeLicense = service();
        LicenseStatusModel status = activeLicense.cachedState().revalidateIfFileChanged();
        if (isExpired(status)) {
            return activeLicense.revalidate("expiry");
        }
        return status;
    }

    public LicenseAccessSnapshot access() {
        return LicenseAccessSnapshot.from(refresh());
    }

    public boolean allows(LicenseRequirement requirement) {
        try {
            require(requirement);
            return true;
        } catch (LicenseAccessException | LicenseFeatureAccessException ignored) {
            return false;
        } catch (RuntimeException error) {
            logger.warn("License access check failed closed for module '{}'.", moduleName, error);
            return false;
        }
    }

    public LicenseStatusModel require(LicenseRequirement requirement) {
        Objects.requireNonNull(requirement, "License requirement is required.");
        LicenseStatusModel status = refresh();
        if (!status.licensed) {
            throw new LicenseAccessException(status);
        }

        return switch (requirement.match()) {
            case LICENSED -> status;
            case ALL -> requireAllFeatures(status, requirement.features());
            case ANY -> service().requireAnyFeature(requirement.features().toArray(String[]::new));
        };
    }

    public RouteHandler wrap(LicenseRequirement requirement, RouteHandler delegate) {
        Objects.requireNonNull(delegate, "Route handler is required.");
        return (request, response) -> {
            try {
                require(requirement);
            } catch (LicenseFeatureAccessException error) {
                throw new ApiException(
                        HttpServletResponse.SC_FORBIDDEN,
                        "FEATURE_NOT_LICENSED",
                        moduleName + " feature is not licensed.",
                        Map.of(
                                "requiredFeatures", error.features(),
                                "match", requirement.wireMatch(),
                                "license", LicenseAccessSnapshot.from(error.status())
                        )
                );
            } catch (LicenseAccessException error) {
                throw new ApiException(
                        HttpServletResponse.SC_FORBIDDEN,
                        "MODULE_UNLICENSED",
                        moduleName + " is not licensed.",
                        LicenseAccessSnapshot.from(error.status())
                );
            }
            return delegate.handle(request, response);
        };
    }

    private LicenseStatusModel requireAllFeatures(LicenseStatusModel status, List<String> requiredFeatures) {
        List<String> licensedFeatures = status.features == null
                ? List.of()
                : status.features.stream()
                    .filter(Objects::nonNull)
                    .map(feature -> feature.trim().toLowerCase(Locale.ROOT))
                    .toList();
        List<String> missing = new ArrayList<>();
        for (String feature : requiredFeatures) {
            if (!licensedFeatures.contains(feature)) {
                missing.add(feature);
            }
        }
        if (!missing.isEmpty()) {
            throw new LicenseFeatureAccessException(missing, status);
        }
        return status;
    }

    private boolean isExpired(LicenseStatusModel status) {
        if (status == null || !status.licensed || status.expiresAt == null || status.expiresAt.isBlank()) {
            return false;
        }
        try {
            return !Instant.parse(status.expiresAt.trim()).isAfter(Instant.now());
        } catch (DateTimeParseException ignored) {
            return true;
        }
    }

    private void monitorLicense() {
        try {
            refresh();
        } catch (RuntimeException error) {
            logger.warn("License monitor refresh failed for module '{}'.", moduleName, error);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
