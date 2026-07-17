package com.jw.ignition.auth.core;

import com.inductiveautomation.ignition.common.auth.security.level.SecurityLevelConfig;
import com.inductiveautomation.ignition.common.auth.web.WebAuthUser;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.jw.ignition.auth.persistence.AppAuthConfigRecord;
import com.jw.ignition.common.service.AbstractRuntimeService;
import com.jw.ignition.common.util.Json;
import simpleorm.dataset.SQuery;

import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthCore extends AbstractRuntimeService {
    private static final String SECURITY_LEVEL_ROLE_PREFIX = "Authenticated/Roles/";
    private final GatewayContext gatewayContext;
    private final AppAuthOptions options;
    private final AtomicReference<AppAuthConfig> configCache;

    public record SecurityLevelRole(String path, String name, String label) {}

    public static final class RevisionConflictException extends RuntimeException {
        private final int expectedRevision;
        private final int currentRevision;

        public RevisionConflictException(int expectedRevision, int currentRevision) {
            super("Revision conflict. Expected " + expectedRevision + ", current " + currentRevision + ".");
            this.expectedRevision = expectedRevision;
            this.currentRevision = currentRevision;
        }

        public int expectedRevision() { return expectedRevision; }
        public int currentRevision() { return currentRevision; }
    }

    public AuthCore(GatewayContext gatewayContext, AppAuthOptions options) {
        this.gatewayContext = Objects.requireNonNull(gatewayContext, "gatewayContext");
        this.options = Objects.requireNonNull(options, "options");
        this.configCache = new AtomicReference<>(AppAuthConfig.defaultConfig(options));
    }

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------

    @Override
    protected void setupProcess() {
        try {
            gatewayContext.getSchemaUpdater().updatePersistentRecords(AppAuthConfigRecord.META);
            createConfigIfMissing();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set up auth config persistence", e);
        }
    }

    @Override
    protected void startupProcess() {
        loadConfig();
    }

    @Override
    protected void shutdownProcess() {
        // No resources to release in the simple version.
    }

    public AppAuthOptions options() { return options; }

    // ------------------------------------------------------------
    // Auth context creation
    // ------------------------------------------------------------

    public Optional<AppAuthContext> gatewayWebUser(RequestContext request) {
        var userOpt = WebUiSession.WebUiSessionAccessControl.getWebAuthUser(request);
        return userOpt.flatMap(user -> fromWebAuthUser(user, AppAuthSource.GATEWAY_WEB_UI));
    }

    public Optional<AppAuthContext> fromWebAuthUser(WebAuthUser user, AppAuthSource source) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(source, "source");
        String username = firstNonBlank(user.getUserName(), user.getId());
        if (username.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new AppAuthContext(
                source,
                username,
                toSecurityLevelRolePaths(user.getRoles()),
                List.of(),
                List.of()
        ));
    }

    public AppAuthContext requireGatewayWebUser(RequestContext request) {
        return gatewayWebUser(request).orElseThrow(() -> new SecurityException("Gateway Web UI user is not authenticated"));
    }

    public AppAuthContext requireWebPermission(RequestContext request, String permissionKey) {
        AppAuthContext actor = requireGatewayWebUser(request);
        requirePermission(actor, permissionKey);
        return actor;
    }

    public AppAuthContext fromPerspectiveSession(
            String username,
            Collection<String> roles,
            Collection<String> securityLevels,
            Collection<String> claims
    ) {
        return new AppAuthContext(
                AppAuthSource.PERSPECTIVE_SESSION,
                username,
                toSecurityLevelRolePaths(roles),
                securityLevels,
                claims
        );
    }

    public static String roleAuthority(String rolePath) {
        return "role:" + securityLevelRolePath(rolePath);
    }

    public static String securityLevelRolePath(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role cannot be blank");
        }
        String trimmed = role.trim();
        if (trimmed.startsWith(SECURITY_LEVEL_ROLE_PREFIX)) {
            return trimmed;
        }
        return SECURITY_LEVEL_ROLE_PREFIX + trimmed;
    }

    public Set<String> toSecurityLevelRolePaths(Collection<String> roles) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (roles == null) {
            return out;
        }

        Set<String> availablePaths = new HashSet<>();
        securityLevelRolesConfig().getChildren().forEach(role ->
                availablePaths.add(SECURITY_LEVEL_ROLE_PREFIX + role.getName())
        );

        for (String value : roles) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String path = securityLevelRolePath(value);
            if (availablePaths.contains(path)) {
                out.add(path);
            }
        }
        return out;
    }

    public List<SecurityLevelRole> securityLevelRoles() {
        return securityLevelRolesConfig().getChildren().stream()
                .map(role -> {
                    String name = role.getName();
                    String path = SECURITY_LEVEL_ROLE_PREFIX + name;
                    return new SecurityLevelRole(path, name, name);
                })
                .sorted(Comparator.comparing(SecurityLevelRole::path))
                .toList();
    }

    private SecurityLevelConfig securityLevelRolesConfig() {
        var manager = gatewayContext.getSecurityLevelManager();
        var configs = manager.getSecurityLevelsConfig();
        var authenticated = SecurityLevelConfig
                .find(configs, "Authenticated")
                .orElseThrow(() -> new IllegalStateException("Missing Security/Levels Authenticated node."));
        return authenticated.find("Roles")
                .orElseThrow(() -> new IllegalStateException("Missing Security/Levels Authenticated/Roles node."));
    }

    // ------------------------------------------------------------
    // Permission checks
    // ------------------------------------------------------------

    public boolean hasPermission(AppAuthContext actor, String permissionKey) {
        return configCache.get().isAllowed(actor, permissionKey);
    }

    public void requirePermission(AppAuthContext actor, String permissionKey) {
        if (!hasPermission(actor, permissionKey)) {
            throw new SecurityException("User '" + actor.username() + "' does not have permission: " + permissionKey);
        }
    }

    public Map<String, Boolean> permissionsFor(AppAuthContext actor) {
        AppAuthConfig config = configCache.get();
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        for (String key : options.permissionKeys()) {
            result.put(key, config.isAllowed(actor, key));
        }
        return result;
    }

    // ------------------------------------------------------------
    // Config storage
    // ------------------------------------------------------------

    public AppAuthConfig currentConfig() {
        return configCache.get().copy();
    }

    public synchronized AppAuthConfig updateConfig(AppAuthConfig incoming, String actor, int expectedRevision) {
        Objects.requireNonNull(incoming, "incoming");
        AppAuthConfig candidate = incoming.copy();
        candidate.validate(options);
        candidate.normalize(options);

        AppAuthConfigRecord record = findConfigRecord();
        if (record == null) {
            if (expectedRevision != 0) throw new RevisionConflictException(expectedRevision, 0);
            candidate.setRevision(1);
            insertConfigRecord(candidate, actor);
            configCache.set(candidate);
            return candidate.copy();
        }

        if (record.getRevision() != expectedRevision) {
            throw new RevisionConflictException(expectedRevision, record.getRevision());
        }

        candidate.setRevision(record.getRevision() + 1);
        record.setConfigJson(Json.GSON.toJson(candidate));
        record.setRevision(candidate.getRevision());
        record.setUpdatedBy(actor);
        record.setUpdatedAt(new Date());
        pi().save(record);
        configCache.set(candidate);
        return candidate.copy();
    }

    public synchronized AppAuthConfig resetConfig(String actor, int expectedRevision) {
        AppAuthConfig defaults = AppAuthConfig.defaultConfig(options);
        defaults.setRevision(expectedRevision);
        return updateConfig(defaults, actor, expectedRevision);
    }

    public synchronized AppAuthConfig resetConfig(String actor) {
        AppAuthConfigRecord record = findConfigRecord();
        return resetConfig(actor, record == null ? 0 : record.getRevision());
    }

    private synchronized void createConfigIfMissing() {
        if (findConfigRecord() != null) {
            return;
        }

        AppAuthConfig defaults = AppAuthConfig.defaultConfig(options);
        insertConfigRecord(defaults, "system");
        configCache.set(defaults);
    }

    private synchronized void loadConfig() {
        try {
            AppAuthConfigRecord record = findConfigRecord();
            if (record == null) {
                logger.error("Auth config record is missing after setup. Using in-memory Administrator-only defaults.");
                configCache.set(AppAuthConfig.defaultConfig(options));
                return;
            }

            AppAuthConfig loaded = Json.GSON.fromJson(record.getConfigJson(), AppAuthConfig.class);
            loaded.normalize(options);
            loaded.setRevision(record.getRevision());
            configCache.set(loaded);
        } catch (Exception e) {
            logger.error("Failed to load auth config. Using in-memory Administrator-only defaults.", e);
            configCache.set(AppAuthConfig.defaultConfig(options));
        }
    }

    private void insertConfigRecord(AppAuthConfig config, String actor) {
        AppAuthConfigRecord record = pi().createNew(AppAuthConfigRecord.META);
        record.setNamespace(options.namespace());
        record.setConfigJson(Json.GSON.toJson(config));
        record.setRevision(config.getRevision());
        record.setUpdatedBy(actor);
        record.setUpdatedAt(new Date());
        pi().save(record);
    }

    private AppAuthConfigRecord findConfigRecord() {
        List<AppAuthConfigRecord> records = pi().query(new SQuery<>(AppAuthConfigRecord.META));
        if (records == null) return null;
        AppAuthConfigRecord match = null;
        for (AppAuthConfigRecord record : records) {
            if (!options.namespace().equals(record.getNamespace())) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException(
                        "Multiple auth config records found for namespace: " + options.namespace()
                );
            }
            match = record;
        }
        return match;
    }

    private PersistenceInterface pi() {
        return gatewayContext.getPersistenceInterface();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? "" : second.trim();
    }
}
