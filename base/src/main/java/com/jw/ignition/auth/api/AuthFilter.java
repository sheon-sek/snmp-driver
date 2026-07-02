package com.jw.ignition.auth.api;

import com.inductiveautomation.ignition.common.auth.web.WebAuthUser;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import com.inductiveautomation.perspective.gateway.api.WebAuthStatus;
import com.inductiveautomation.perspective.gateway.session.InternalSession;
import com.inductiveautomation.perspective.gateway.session.PerspectiveSessionCollection;
import com.jw.ignition.auth.core.AppAuthContext;
import com.jw.ignition.auth.core.AppAuthSource;
import com.jw.ignition.auth.core.AuthCore;
import com.jw.ignition.common.api.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.security.Principal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AuthFilter {

    public static final String ATTR_AUTH_CONTEXT = "eetarpSdk.authContext";
    public static final String ATTR_USER_NAME = "eetarpSdk.userName";
    public static final String ATTR_DISPLAY_NAME = "eetarpSdk.displayName";
    public static final String ATTR_USER_HOST = "eetarpSdk.userHost";
    public static final String ATTR_USER_ROLES = "eetarpSdk.userRoles";
    public static final String ATTR_AUTH_SOURCE = "eetarpSdk.authSource";

    public static final String HEADER_PERSPECTIVE_SESSION_ID = "X-EEAUTH-Session-Id";
    public static final String HEADER_PERSPECTIVE_PROJECT = "X-EEAUTH-Project";

    private final GatewayContext gatewayContext;
    private final AuthCore auth;

    public AuthFilter(GatewayContext gatewayContext, AuthCore auth) {
        this.gatewayContext = gatewayContext;
        this.auth = auth;
    }

    /**
     * 只要求已认证，不要求某个业务 permission。
     * 适合 /auth/me 这类 endpoint。
     */
    public RouteHandler authenticated(RouteHandler delegate) {
        return wrap(null, delegate);
    }

    /**
     * 要求已认证 + 具备指定 permission。
     */
    public RouteHandler require(String permissionKey, RouteHandler delegate) {
        if (isBlank(permissionKey)) {
            throw new IllegalArgumentException("permissionKey cannot be blank");
        }
        return wrap(permissionKey, delegate);
    }

    private RouteHandler wrap(String permissionKey, RouteHandler delegate) {
        return (requestContext, response) -> {
            HttpServletRequest request = requestContext.getRequest();
            request.setAttribute(ATTR_USER_HOST, request.getRemoteAddr());

            AuthResolveResult resolved = resolveIdentity(requestContext, request);

            if (!resolved.authenticated()) {
                return ApiResponses.unauthenticated(response, resolved.message());
            }

            AppAuthContext actor = resolved.authContext();

            if (!isBlank(permissionKey) && !auth.hasPermission(actor, permissionKey)) {
                return ApiResponses.unauthorized(response, "Missing permission: " + permissionKey);
            }

            applyRequestAttributes(request, actor);

            return delegate.handle(requestContext, response);
        };
    }

    private AuthResolveResult resolveIdentity(RequestContext requestContext, HttpServletRequest request) {
        boolean perspectiveHeaderPresent = hasPerspectiveHeaders(request);

        if (perspectiveHeaderPresent) {
            return resolvePerspectiveIdentity(request)
                    .map(AuthResolveResult::authenticated)
                    .orElseGet(() -> AuthResolveResult.unauthenticated("Invalid or unauthenticated Perspective session."));
        }

        return resolveGatewayIdentity(requestContext, request)
                .map(AuthResolveResult::authenticated)
                .orElseGet(() -> AuthResolveResult.unauthenticated("No authenticated Gateway Web UI user."));
    }

    private boolean hasPerspectiveHeaders(HttpServletRequest request) {
        return !isBlank(request.getHeader(HEADER_PERSPECTIVE_SESSION_ID))
                || !isBlank(request.getHeader(HEADER_PERSPECTIVE_PROJECT));
    }

    private Optional<AppAuthContext> resolveGatewayIdentity(RequestContext requestContext, HttpServletRequest request) {
        Optional<WebAuthUser> webUser = WebUiSession.WebUiSessionAccessControl.getWebAuthUser(requestContext);
        if (webUser.isPresent()) {
            WebAuthUser user = webUser.get();
            return Optional.of(new AppAuthContext(
                    AppAuthSource.GATEWAY_WEB_UI,
                    user.getUserName(),
                    auth.toSecurityLevelRolePaths(user.getRoles()),
                    Set.of(),
                    Set.of()
            ));
        }

        /*
         * 如果你完全使用 OPEN_ROUTE，WebUiSessionAccessControl.getWebAuthUser(requestContext)
         * 不一定会被 IA 填充。
         *
         * 所以这里保留 Principal fallback。
         * 若你后续发现 Gateway Web UI request 下 principal 为空，就不能只靠 HTTP filter；
         * 那时 Gateway Web UI 入口仍需要 WebUiSessionAccessControl，或要使用 IA 内部 session API。
         */
        Principal principal = request.getUserPrincipal();
        if (principal == null || isBlank(principal.getName())) {
            return Optional.empty();
        }

        String username = trim(principal.getName());

        return Optional.of(new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                username,
                Set.of(),
                Set.of(),
                Set.of()
        ));
    }

    private Optional<AppAuthContext> resolvePerspectiveIdentity(HttpServletRequest request) {
        String sessionId = trim(request.getHeader(HEADER_PERSPECTIVE_SESSION_ID));
        String projectName = trim(request.getHeader(HEADER_PERSPECTIVE_PROJECT));

        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            return Optional.empty();
        }

        PerspectiveContext perspective = PerspectiveContext.get(gatewayContext);
        if (perspective == null || perspective.getSessionMonitor() == null) {
            return Optional.empty();
        }

        PerspectiveSessionCollection collection = perspectiveCollection(httpSession).orElse(null);

        Optional<InternalSession> session = findPerspectiveSession(
                perspective,
                collection,
                httpSession,
                sessionId,
                projectName
        );

        return session.flatMap(this::identityFromPerspectiveSession);
    }

    private Optional<InternalSession> findPerspectiveSession(
            PerspectiveContext perspective,
            PerspectiveSessionCollection collection,
            HttpSession httpSession,
            String sessionId,
            String projectName
    ) {
        if (!isBlank(sessionId)) {
            try {
                UUID uuid = UUID.fromString(sessionId);

                return perspective.getSessionMonitor()
                        .findSession(uuid)
                        .filter(candidate -> belongsToHttpSession(candidate, collection, httpSession))
                        .filter(candidate -> isBlank(projectName) || projectName.equals(candidate.getProjectName()));

            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }

        if (collection != null && !isBlank(projectName)) {
            return collection.findProjectSession(projectName);
        }

        return Optional.empty();
    }

    private Optional<PerspectiveSessionCollection> perspectiveCollection(HttpSession httpSession) {
        Object value = httpSession.getAttribute(PerspectiveSessionCollection.SESSION_KEY);
        if (value instanceof PerspectiveSessionCollection collection) {
            return Optional.of(collection);
        }
        return Optional.empty();
    }

    private boolean belongsToHttpSession(
            InternalSession session,
            PerspectiveSessionCollection collection,
            HttpSession httpSession
    ) {
        PerspectiveSessionCollection sessionCollection = session.getSessionCollection();

        if (collection != null && sessionCollection == collection) {
            return true;
        }

        return sessionCollection.getHttpSession()
                .map(HttpSession::getId)
                .filter(id -> id.equals(httpSession.getId()))
                .isPresent();
    }

    private Optional<AppAuthContext> identityFromPerspectiveSession(InternalSession session) {
        WebAuthStatus authStatus = session.getWebAuthStatus();

        if (authStatus == null || !authStatus.isAuthenticated()) {
            return Optional.empty();
        }

        return authStatus.getUser().map(user -> identityFromWebAuthUser(session, user));
    }

    private AppAuthContext identityFromWebAuthUser(InternalSession session, WebAuthUser user) {
        String username = trim(user.getUserName());

        if (isBlank(username)) {
            username = trim(user.getId());
        }

        Set<String> roles = user.getRoles().stream()
                .map(AuthFilter::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toSet());
        Set<String> rolePaths = auth.toSecurityLevelRolePaths(roles);

        /*
         * 这里先只放 roles。
         * 如果你后续能从 WebAuthStatus / session auth context 取到 security levels，
         * 再统一放进 securityLevels。
         */
        return new AppAuthContext(
                AppAuthSource.PERSPECTIVE_SESSION,
                username,
                rolePaths,
                Set.of(),
                Set.of()
        );
    }

    private void applyRequestAttributes(HttpServletRequest request, AppAuthContext actor) {
        request.setAttribute(ATTR_AUTH_CONTEXT, actor);
        request.setAttribute(ATTR_AUTH_SOURCE, actor.source().name());
        request.setAttribute(ATTR_USER_NAME, actor.username());
        request.setAttribute(ATTR_DISPLAY_NAME, actor.username());
        request.setAttribute(ATTR_USER_ROLES, actor.roles());
    }

    public static Optional<AppAuthContext> getAuthContext(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_AUTH_CONTEXT);
        if (value instanceof AppAuthContext authContext) {
            return Optional.of(authContext);
        }
        return Optional.empty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
