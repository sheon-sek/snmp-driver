package com.jw.ignition.auth.core;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.jw.ignition.auth.persistence.AppAuthConfigRecord;
import com.jw.ignition.common.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simpleorm.dataset.SQuery;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthCore {
    private static final Logger log = LoggerFactory.getLogger(AuthCore.class);
    private static final String SECURITY_LEVEL_ROLE_PREFIX = "Authenticated/Roles/";
    private final GatewayContext gatewayContext;
    private final AppAuthOptions options;
    private final AtomicReference<AppAuthConfig> configCache;

    public record SecurityLevelRole(String path, String name, String label) {}

    public AuthCore(GatewayContext gatewayContext, AppAuthOptions options) {
        this.gatewayContext = gatewayContext;
        this.options = options;
        this.configCache = new AtomicReference<>(AppAuthConfig.defaultConfig(options));
    }

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------

    public void setup() {
        try {
            gatewayContext.getSchemaUpdater().updatePersistentRecords(AppAuthConfigRecord.META);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to setup auth config PersistentRecord schema", e);
        }
    }

    public void startup() {
        loadOrCreateConfig();
    }

    public void shutdown() {
        // No resources to release in the simple version.
    }

    public AppAuthOptions options() { return options; }

    // ------------------------------------------------------------
    // Auth context creation
    // ------------------------------------------------------------

    public Optional<AppAuthContext> gatewayWebUser(RequestContext request) {
        var userOpt = WebUiSession.WebUiSessionAccessControl.getWebAuthUser(request);
        if (userOpt.isEmpty()) return Optional.empty();
        var user = userOpt.get();

        // WebAuthUser roles are stable and easy to use.
        // If you can read granted security levels from your SDK context, add them here.
        return Optional.of(new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                user.getUserName(),
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
        return new AppAuthContext(AppAuthSource.PERSPECTIVE_SESSION, username, roles, securityLevels, claims);
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

        Map<String, String> available = new LinkedHashMap<>();
        for (SecurityLevelRole role : securityLevelRoles()) {
            available.put(role.name(), role.path());
            available.put(role.path(), role.path());
        }

        for (String value : roles) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            String path = available.get(trimmed);
            if (path != null) {
                out.add(path);
            }
        }
        return out;
    }

    public List<SecurityLevelRole> securityLevelRoles() {
        var manager = gatewayContext.getSecurityLevelManager();
        var configs = manager.getSecurityLevelsConfig();
        var authenticated = com.inductiveautomation.ignition.common.auth.security.level.SecurityLevelConfig
                .find(configs, "Authenticated")
                .orElseThrow(() -> new IllegalStateException("Missing Security/Levels Authenticated node."));
        var roles = authenticated.find("Roles")
                .orElseThrow(() -> new IllegalStateException("Missing Security/Levels Authenticated/Roles node."));

        return roles.getChildren().stream()
                .map(role -> {
                    String name = role.getName();
                    String path = "Authenticated/Roles/" + name;
                    return new SecurityLevelRole(path, name, name);
                })
                .sorted(Comparator.comparing(SecurityLevelRole::path))
                .toList();
    }

    // ------------------------------------------------------------
    // Permission checks
    // ------------------------------------------------------------

    public boolean hasPermission(AppAuthContext actor, String permissionKey) {
        return currentConfig().isAllowed(actor, permissionKey);
    }

    public void requirePermission(AppAuthContext actor, String permissionKey) {
        if (!hasPermission(actor, permissionKey)) {
            throw new SecurityException("User '" + actor.username() + "' does not have permission: " + permissionKey);
        }
    }

    public Map<String, Boolean> permissionsFor(AppAuthContext actor) {
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        for (String key : options.permissionKeys()) {
            result.put(key, hasPermission(actor, key));
        }
        return result;
    }

    // ------------------------------------------------------------
    // Config storage
    // ------------------------------------------------------------

    public AppAuthConfig currentConfig() {
        return configCache.get();
    }

    public synchronized AppAuthConfig updateConfig(AppAuthConfig incoming, String actor, int expectedRevision) {
        incoming.validate(options);
        incoming.normalize(options);

        AppAuthConfigRecord record = findConfigRecord();
        if (record == null) {
            if (expectedRevision != 0) throw new IllegalStateException("Revision conflict. Current revision is 0.");
            incoming.setRevision(1);
            insertConfigRecord(incoming, actor);
            configCache.set(incoming);
            return incoming;
        }

        if (record.getRevision() != expectedRevision) {
            throw new IllegalStateException("Revision conflict. Expected " + expectedRevision + ", current " + record.getRevision());
        }

        incoming.setRevision(record.getRevision() + 1);
        record.setConfigJson(Json.GSON.toJson(incoming));
        record.setRevision(incoming.getRevision());
        record.setUpdatedBy(actor);
        record.setUpdatedAt(new Date());
        pi().save(record);
        configCache.set(incoming);
        return incoming;
    }

    public synchronized AppAuthConfig resetConfig(String actor, int expectedRevision) {
        AppAuthConfig defaults = AppAuthConfig.defaultConfig(options);
        defaults.setRevision(expectedRevision);
        return updateConfig(defaults, actor, expectedRevision);
    }

    private synchronized void loadOrCreateConfig() {
        try {
            AppAuthConfigRecord record = findConfigRecord();
            if (record == null) {
                AppAuthConfig defaults = AppAuthConfig.defaultConfig(options);
                insertConfigRecord(defaults, "system");
                configCache.set(defaults);
                return;
            }

            AppAuthConfig loaded = Json.GSON.fromJson(record.getConfigJson(), AppAuthConfig.class);
            loaded.normalize(options);
            loaded.setRevision(record.getRevision());
            configCache.set(loaded);
        } catch (Exception e) {
            log.error("Failed to load auth config. Using in-memory Administrator-only defaults.", e);
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
        for (AppAuthConfigRecord record : records) {
            if (options.namespace().equals(record.getNamespace())) return record;
        }
        return null;
    }

    private PersistenceInterface pi() {
        return gatewayContext.getPersistenceInterface();
    }
}
