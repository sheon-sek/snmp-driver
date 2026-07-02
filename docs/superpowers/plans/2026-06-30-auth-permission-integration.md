# Auth Permission Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a simple end-to-end auth permission workflow where Permission Control lists Ignition Security/Levels `Authenticated/Roles` roles and assigns module permissions to those roles.

**Architecture:** Keep reusable auth logic in `:base`, keep `:gateway` as boilerplate orchestration plus module permission catalog, and keep frontend changes inside the existing `src/lib/types`, `src/lib/api`, `src/lib/query`, `src/components`, and `src/pages` structure. Do not add alternate role catalogs or deep class hierarchies.

**Tech Stack:** Java 17, Ignition 8.3 Gateway SDK, JUnit Jupiter, React 18, TypeScript, RTK Query, React Router, shadcn/ui components already present in `web-ui`.

---

## Design Constraints

- Repository root: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate`
- Spec: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\docs\superpowers\specs\2026-06-30-auth-permission-integration-design.md`
- Keep `:base` as shared boilerplate auth library.
- Keep `:gateway` clean: permission catalog, GatewayHook wiring, route orchestration only.
- Keep frontend API work in the current pattern:
  - Types: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\types\api`
  - Endpoints: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\api`
  - Hooks/helpers: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\query`
  - Pages: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages`
- Do not create alternate role catalog routes, alternate role catalog UI, or non-Security-Levels role authorities.
- Authority format for roles is exactly `role:<rolePath>`.
- The role path is the canonical path from Security/Levels `Authenticated/Roles`.

## Context7 Notes

Context7 lookup used official Ignition 8.3.6 Javadocs:

- `SecurityLevelManager.getSecurityLevelsConfig()` returns configured `SecurityLevelConfig` values.
- `SecurityLevelConfig.find(...)` can locate security levels by path.
- `ReservedSecurityLevel` includes `Authenticated`, `Roles`, and `SecurityZones`.
- `ClientReqSession.User` exposes username, roles, and security levels.

Implementation must compile-check exact SDK method names while working in the repo; if an SDK method name differs from the plan, use the closest official 8.3 API and keep the same public module behavior.

## File Map

### Backend `:base`

- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\build.gradle`
  - Add JUnit dependencies for auth-library tests.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AppAuthContext.java`
  - Keep one clear authority builder.
  - Treat `roles` as Security Levels role paths.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AppAuthConfig.java`
  - Keep config normalize simple.
  - Keep `role:`, `securityLevel:`, `claim:` prefixes.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AuthCore.java`
  - Add a small nested `SecurityLevelRole` record.
  - Add a simple method that returns `Authenticated/Roles` role entries.
  - Add a simple method that maps current actor role names to canonical Security Levels role paths.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\api\AuthFilter.java`
  - Use `AuthCore` to convert current actor roles to canonical role paths before building `AppAuthContext`.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\api\AuthRoutes.java`
  - Fix `/auth/me` response property names to match frontend type.
  - Add `/authorization/security-level-roles` handler.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\api\AuthRouteRegistrar.java`
  - Register `GET /authorization/security-level-roles`.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\test\java\com\jw\ignition\auth\core\AppAuthContextTest.java`
  - Test authority building stays understandable.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\test\java\com\jw\ignition\auth\core\AppAuthConfigTest.java`
  - Test permission matching and unknown prefix cleanup.

### Backend `:gateway`

- Keep existing: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\gateway\src\main\java\com\jw\ignitionsdkboilerplate\gateway\GatewayHook.java`
  - No feature logic added here.
- Keep existing: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\gateway\src\main\java\com\jw\ignitionsdkboilerplate\gateway\auth\AppPermission.java`
  - Permission catalog remains module-specific.
- Existing tests in `gateway/src/test/java` remain only for gateway-specific behavior.

### Frontend

- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\types\api\auth.ts`
  - Auth and permission response types.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\types\api\index.ts`
  - Export auth types.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\api\index.ts`
  - Add RTK Query tag types.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\api\auth.ts`
  - RTK Query endpoints for auth and permission config.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\query\auth.ts`
  - Human-readable hooks and helper functions.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\components\auth\RouteGuard.tsx`
  - Small route guard.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\app\appRoutes.tsx`
  - Add Permission Control route and permissions.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\App.tsx`
  - Wrap route elements with `RouteGuard`.
  - Filter sidebar groups by current user permissions.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\Auth\UserDeniedPage\index.tsx`
  - Access denied fallback.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\Auth\LoginRequiredPage\index.tsx`
  - Login required fallback.
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\Settings\PermissionControlPage\index.tsx`
  - Role list and permission dialog.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\HomePage\index.tsx`
  - Add current user and permission summary.
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\index.ts`
  - Export new pages.

---

### Task 1: Add focused `:base` auth tests

**Files:**

- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\build.gradle`
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\test\java\com\jw\ignition\auth\core\AppAuthContextTest.java`
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\test\java\com\jw\ignition\auth\core\AppAuthConfigTest.java`

- [x] **Step 1: Add JUnit to `:base`**

Patch `base/build.gradle`:

```gradle
plugins {
    id 'java-library'
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(property("javaVersion").toInteger()))
    }
}

dependencies {
    compileOnly "com.inductiveautomation.ignitionsdk:ignition-common:${property("ignitionVersion")}"
    compileOnly "com.inductiveautomation.ignitionsdk:gateway-api:${property("ignitionVersion")}"
    compileOnly "com.inductiveautomation.ignitionsdk:perspective-gateway:${property("ignitionVersion")}"
    compileOnly project(":common")

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.3'
}

tasks.withType(Test).configureEach {
    useJUnitPlatform()
}
```

- [x] **Step 2: Add authority test**

Create `AppAuthContextTest.java`:

```java
package com.jw.ignition.auth.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppAuthContextTest {

    @Test
    void authoritiesUseSecurityLevelRolePaths() {
        AppAuthContext actor = new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                "alice",
                List.of("Authenticated/Roles/Engineer"),
                List.of("Authenticated"),
                List.of("canEdit")
        );

        assertEquals("alice", actor.username());
        assertTrue(actor.roles().contains("Authenticated/Roles/Engineer"));
        assertTrue(actor.authorities().contains("role:Authenticated/Roles/Engineer"));
        assertTrue(actor.authorities().contains("securityLevel:Authenticated"));
        assertTrue(actor.authorities().contains("claim:canEdit"));
    }
}
```

- [x] **Step 3: Add config permission test**

Create `AppAuthConfigTest.java`:

```java
package com.jw.ignition.auth.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppAuthConfigTest {

    @Test
    void allowsPermissionByRolePathAuthority() {
        AppAuthOptions options = new AppAuthOptions(
                "test",
                "authorization.manage",
                List.of(new AppPermissionDef("health.access", "Health", "View health"))
        );

        AppAuthConfig config = AppAuthConfig.defaultConfig(options);
        Map<String, Set<String>> permissions = new LinkedHashMap<>();
        permissions.put("health.access", new LinkedHashSet<>(List.of("role:Authenticated/Roles/Engineer")));
        permissions.put("authorization.manage", new LinkedHashSet<>());
        config.setPermissions(permissions);
        config.normalize(options);

        AppAuthContext engineer = new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                "alice",
                List.of("Authenticated/Roles/Engineer"),
                List.of(),
                List.of()
        );

        AppAuthContext operator = new AppAuthContext(
                AppAuthSource.GATEWAY_WEB_UI,
                "bob",
                List.of("Authenticated/Roles/Operator"),
                List.of(),
                List.of()
        );

        assertTrue(config.isAllowed(engineer, "health.access"));
        assertFalse(config.isAllowed(operator, "health.access"));
    }
}
```

- [x] **Step 4: Verification command for execution phase**

Status: USER_CONFIRMED_VERIFICATION_DONE. User reported this verification step was completed and committed in `caec48e` (`feat: auth permission integration Task 1`).

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
.\gradlew.bat --no-daemon --console plain :base:test
```

Expected: tests compile and pass.

---

### Task 2: Add Security Levels role catalog in `AuthCore`

**Files:**

- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AuthCore.java`

- [x] **Step 1: Add a small response record**

Inside `AuthCore`, add this public nested record near fields or before lifecycle methods:

```java
public record SecurityLevelRole(String path, String name, String label) {}
```

- [x] **Step 2: Add role authority helper**

Add this method in `AuthCore`:

```java
public static String roleAuthority(String rolePath) {
    if (rolePath == null || rolePath.isBlank()) {
        throw new IllegalArgumentException("rolePath cannot be blank");
    }
    return "role:" + rolePath.trim();
}
```

- [x] **Step 3: Add canonical role path mapper**

Add this method in `AuthCore`:

```java
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
```

- [x] **Step 4: Add Security Levels role list**

Add a compact method that uses the Ignition SDK role tree. Keep it in `AuthCore` so `gateway` stays clean:

```java
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
```

If local Ignition 8.3 API uses different child/name accessors, keep this same method signature and adjust only the SDK calls. Do not change frontend contract.

- [x] **Step 5: Verification command for execution phase**

Status: USER_CONFIRMED_VERIFICATION_DONE. User reported this verification step was completed.

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
.\gradlew.bat --no-daemon --console plain :base:compileJava
```

Expected: `AuthCore` compiles. If SDK accessor names differ, fix only Task 2 Step 4.

---

### Task 3: Use Security Levels role paths in auth resolution and API responses

**Files:**

- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AppAuthContext.java`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AppAuthConfig.java`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\api\AuthFilter.java`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\api\AuthRoutes.java`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\api\AuthRouteRegistrar.java`
- Review fix: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AuthCore.java`
- Review fix: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\base\src\main\java\com\jw\ignition\auth\core\AppAuthOptions.java`

- [x] **Step 1: Use the shared role authority helper**

In `AppAuthContext.buildAuthorities`, replace direct string concatenation for roles:

```java
private static Set<String> buildAuthorities(Set<String> roles, Set<String> levels, Set<String> claims) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String role : roles) out.add(AuthCore.roleAuthority(role));
    for (String level : levels) out.add("securityLevel:" + level);
    for (String claim : claims) out.add("claim:" + claim);
    return out;
}
```

In `AppAuthConfig.adminAuthorities`, replace direct role concatenation:

```java
private static Set<String> adminAuthorities(AppAuthOptions options) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String role : options.superAdminRoles()) out.add(AuthCore.roleAuthority(role));
    for (String level : options.superAdminSecurityLevels()) out.add("securityLevel:" + level);
    return out;
}
```

- [x] **Step 2: Convert Gateway Web UI roles to canonical paths**

In `AuthFilter.resolveGatewayIdentity`, change the `WebAuthUser` branch:

```java
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
```

Keep the `Principal` fallback authenticated but roleless. It must not invent role paths:

```java
return Optional.of(new AppAuthContext(
        AppAuthSource.GATEWAY_WEB_UI,
        username,
        Set.of(),
        Set.of(),
        Set.of()
));
```

- [x] **Step 3: Convert Perspective roles to canonical paths**

In `AuthFilter.identityFromWebAuthUser`, keep role cleaning, then convert:

```java
Set<String> rolePaths = auth.toSecurityLevelRolePaths(roles);

return new AppAuthContext(
        AppAuthSource.PERSPECTIVE_SESSION,
        username,
        rolePaths,
        Set.of(),
        Set.of()
);
```

- [x] **Step 4: Fix `/auth/me` response names**

In `AuthRoutes.handleAppAuthMe`, use lower camel case keys:

```java
return ApiResponses.ok(Map.of(
        "source", actor.source().toString(),
        "appAuthenticated", true,
        "username", actor.username(),
        "roles", actor.roles(),
        "securityLevels", actor.securityLevels(),
        "authorities", actor.authorities(),
        "permissions", auth.permissionsFor(actor)
));
```

- [x] **Step 5: Add security-level-roles handler**

Add this method to `AuthRoutes`:

```java
public Object handleSecurityLevelRoles(RequestContext request, HttpServletResponse response) {
    return ApiResponses.ok(Map.of(
            "roles", auth.securityLevelRoles()
    ));
}
```

- [x] **Step 6: Register endpoint**

In `AuthRouteRegistrar.register`, add:

```java
json(
        routes,
        HttpMethod.GET,
        "/authorization/security-level-roles",
        true,
        auth.options().authorizationManagePermissionKey(),
        authRoutes::handleSecurityLevelRoles
);
```

- [x] **Step 7: Verification command for execution phase**

Status: CODEX_VERIFICATION_PASS. User later reported `:base:test` failed; Codex reproduced the failure as `NoClassDefFoundError: simpleorm/dataset/SRecordMeta`, fixed the `:base` test runtime classpath, then force-ran the verification command with `--rerun-tasks`.

Implementation notes:

- `AuthCore.roleAuthority(...)` now canonicalizes bare Security Levels role names to `Authenticated/Roles/<name>`.
- `AuthCore.gatewayWebUser(...)`, Gateway Web UI auth filtering, and Perspective auth filtering now resolve WebAuthUser roles through canonical Security Levels role paths.
- `AppAuthConfig.normalize(...)` now cleans admin role/security-level config and migrates persisted `role:<bareName>` authorities to `role:Authenticated/Roles/<bareName>`.
- Static review gates completed: initial spec review found a super-admin role-path mismatch; final spec re-review passed; final code-quality re-review found no open Critical/Important/Minor issues.

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
.\gradlew.bat --no-daemon --console plain :base:test
```

Expected: auth tests pass and base compiles.

Actual Codex verification:

```text
> Task :base:test
BUILD SUCCESSFUL in 32s
6 actionable tasks: 6 executed
```

---

### Task 4: Add frontend auth API types, endpoints, and hooks

**Files:**

- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\types\api\auth.ts`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\types\api\index.ts`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\api\index.ts`
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\api\auth.ts`
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\lib\query\auth.ts`

- [x] **Step 1: Add auth response types**

Create `auth.ts`:

```ts
export interface CurrentUserResponse {
  source: string;
  appAuthenticated: boolean;
  username: string;
  roles: string[];
  securityLevels: string[];
  authorities: string[];
  permissions: Record<string, boolean>;
}

export interface PermissionCatalogItem {
  key: string;
  label: string;
  description: string;
}

export interface AppAuthConfig {
  version: number;
  revision: number;
  superAdminRoles: string[];
  superAdminSecurityLevels: string[];
  permissions: Record<string, string[]>;
}

export interface PermissionConfigResponse {
  config: AppAuthConfig;
  permissionCatalog: PermissionCatalogItem[];
}

export interface UpdatePermissionConfigRequest extends AppAuthConfig {}

export interface SecurityLevelRole {
  path: string;
  name: string;
  label: string;
}

export interface SecurityLevelRolesResponse {
  roles: SecurityLevelRole[];
}
```

- [x] **Step 2: Export auth types**

Add to `lib/types/api/index.ts`:

```ts
export * from "./auth";
```

- [x] **Step 3: Add RTK tags**

Change `baseApi` creation in `lib/api/index.ts`:

```ts
export const baseApi = createApi({
  baseQuery: dynamicQuery,
  tagTypes: ["CurrentUser", "PermissionConfig", "SecurityLevelRoles"],
  endpoints: () => ({}),
});
```

- [x] **Step 4: Add auth API endpoints**

Create `lib/api/auth.ts`:

```ts
import { baseApi } from "./index";
import type {
  AppAuthConfig,
  CurrentUserResponse,
  PermissionConfigResponse,
  SecurityLevelRolesResponse,
  UpdatePermissionConfigRequest,
} from "@/lib/types/api/auth";

export const authApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    currentUser: build.query<CurrentUserResponse, void>({
      query: () => ({ url: "/auth/me", method: "GET" }),
      providesTags: ["CurrentUser"],
    }),
    permissionConfig: build.query<PermissionConfigResponse, void>({
      query: () => ({ url: "/authorization/config", method: "GET" }),
      providesTags: ["PermissionConfig"],
    }),
    securityLevelRoles: build.query<SecurityLevelRolesResponse, void>({
      query: () => ({
        url: "/authorization/security-level-roles",
        method: "GET",
      }),
      providesTags: ["SecurityLevelRoles"],
    }),
    updatePermissionConfig: build.mutation<
      AppAuthConfig,
      UpdatePermissionConfigRequest
    >({
      query: (body) => ({
        url: "/authorization/config",
        method: "POST",
        body,
      }),
      invalidatesTags: ["CurrentUser", "PermissionConfig"],
    }),
    resetPermissionConfig: build.mutation<AppAuthConfig, void>({
      query: () => ({
        url: "/authorization/config/reset",
        method: "POST",
      }),
      invalidatesTags: ["CurrentUser", "PermissionConfig"],
    }),
  }),
});

export const {
  useCurrentUserQuery,
  usePermissionConfigQuery,
  useSecurityLevelRolesQuery,
  useUpdatePermissionConfigMutation,
  useResetPermissionConfigMutation,
} = authApi;
```

- [x] **Step 5: Add query helpers**

Create `lib/query/auth.ts`:

```ts
import {
  useCurrentUserQuery,
  usePermissionConfigQuery,
  useResetPermissionConfigMutation,
  useSecurityLevelRolesQuery,
  useUpdatePermissionConfigMutation,
} from "@/lib/api/auth";
import type { CurrentUserResponse } from "@/lib/types/api/auth";

export function useCurrentUser() {
  return useCurrentUserQuery();
}

export function usePermissionConfig() {
  return usePermissionConfigQuery();
}

export function useSecurityLevelRoles() {
  return useSecurityLevelRolesQuery();
}

export function useUpdatePermissionConfig() {
  return useUpdatePermissionConfigMutation();
}

export function useResetPermissionConfig() {
  return useResetPermissionConfigMutation();
}

export function hasPermission(
  user: CurrentUserResponse | undefined,
  permissionKey: string,
) {
  return user?.permissions?.[permissionKey] === true;
}

export function hasAllPermissions(
  user: CurrentUserResponse | undefined,
  permissionKeys: string[] | undefined,
) {
  if (!permissionKeys || permissionKeys.length === 0) {
    return true;
  }
  return permissionKeys.every((key) => hasPermission(user, key));
}

export function buildRoleAuthority(rolePath: string) {
  return `role:${rolePath}`;
}
```

- [x] **Step 6: Verification command for execution phase**

Status: USER_CONFIRMED_VERIFICATION_DONE. User reported this verification step was completed.

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run lint
```

Expected: TypeScript passes.

---

### Task 5: Add route guard and sidebar permission filtering

**Files:**

- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\components\auth\RouteGuard.tsx`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\App.tsx`

- [x] **Step 1: Add `RouteGuard`**

Create `RouteGuard.tsx`:

```tsx
import type { ReactNode } from "react";
import { getApiErrorMessage, isApiErrorResponse } from "@/lib/api";
import { useCurrentUser } from "@/lib/query/auth";
import { LoginRequiredPage, UserDeniedPage } from "@/pages";

export interface RouteGuardProps {
  requiredPermissions?: string[];
  children: ReactNode;
}

export function RouteGuard({
  requiredPermissions = [],
  children,
}: RouteGuardProps) {
  const { data: user, error, isError, isLoading } = useCurrentUser();

  if (isLoading) {
    return (
      <div className="flex min-h-full items-center justify-center text-sm text-muted-foreground">
        Loading session...
      </div>
    );
  }

  if (isError && isApiErrorResponse(error) && Number(error.status) === 401) {
    return (
      <LoginRequiredPage
        message={getApiErrorMessage(error, "Login required", true)}
      />
    );
  }

  if (!user?.appAuthenticated) {
    return <LoginRequiredPage message="Login required" />;
  }

  const missingPermissions = requiredPermissions.filter(
    (permission) => user.permissions?.[permission] !== true,
  );

  if (missingPermissions.length > 0) {
    return (
      <UserDeniedPage missingPermissions={missingPermissions} user={user} />
    );
  }

  return <>{children}</>;
}
```

Implementation note: Task 5 uses local fallback components inside `RouteGuard.tsx`
for login-required, access-denied, and session-error states so this task does not
depend on Task 6 fallback page files before they exist. Non-401 current-user
query errors render a session error instead of being shown as login required.

- [x] **Step 2: Add sidebar filter helpers in `App.tsx`**

Update `App.tsx` with local helper functions; keep them in the same file to avoid adding small one-use files:

```tsx
import { Route } from "react-router-dom";

import {
  appRoutes,
  flatAppRoutes,
  navigationGroups,
  type NavigationGroup,
  type NavigationItem,
} from "@/app/appRoutes";
import { RouteGuard } from "@/components/auth/RouteGuard";
import { DynamicRouter } from "./components/foundation/DynamicRouter";
import { AppLayout } from "./components/layout/AppLayout";
import { hasAllPermissions, useCurrentUser } from "@/lib/query/auth";
import type { CurrentUserResponse } from "@/lib/types/api/auth";

export default function App() {
  const { data: user } = useCurrentUser();
  const visibleNavigationGroups = filterNavigationGroups(
    navigationGroups,
    user,
  );

  return (
    <DynamicRouter>
      <Route
        element={
          <AppLayout
            appTitle="Ignition SDK Template"
            appSubtitle="Gateway module"
            navigationGroups={visibleNavigationGroups}
            routes={appRoutes}
            sidebarFooter="Powered by Eetarp"
          />
        }
      >
        {flatAppRoutes.map((route) => (
          <Route
            key={route.id}
            path={route.path}
            element={
              <RouteGuard requiredPermissions={route.requiredPermissions}>
                {route.element}
              </RouteGuard>
            }
          />
        ))}
      </Route>
    </DynamicRouter>
  );
}

function filterNavigationGroups(
  groups: NavigationGroup[],
  user: CurrentUserResponse | undefined,
) {
  return groups
    .map((group) => ({
      ...group,
      items: filterNavigationItems(group.items, user),
    }))
    .filter((group) => group.items.length > 0);
}

function filterNavigationItems(
  items: NavigationItem[],
  user: CurrentUserResponse | undefined,
): NavigationItem[] {
  return items
    .filter((item) => hasAllPermissions(user, item.requiredPermissions))
    .map((item) => ({
      ...item,
      children: item.children
        ? filterNavigationItems(item.children, user)
        : undefined,
    }))
    .filter((item) => item.href || (item.children && item.children.length > 0));
}
```

Post-Task 6 pre-Task 7 adjustment: `AppLayout` now reads `useCurrentUser()` and
passes an auth-derived navigation visibility flag into `AppSidebar`.
`AppSidebarContent` is not rendered while auth is loading, when the current-user
query errors or returns 401, or when the current actor is not app-authenticated.
The sidebar shell, header, footer, and protected route rendering remain in place.
`SessionErrorPage` is also extracted under `pages/Auth` and exported through the
page barrel so `RouteGuard` no longer owns fallback page components.

- [x] **Step 3: Verification command for execution phase**

Status: USER_CONFIRMED_VERIFICATION_DONE. User reported this verification step was completed.

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run lint
```

Expected: TypeScript passes.

---

### Task 6: Add Permission Control and fallback pages

**Files:**

- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\Auth\UserDeniedPage\index.tsx`
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\Auth\LoginRequiredPage\index.tsx`
- Create: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\Settings\PermissionControlPage\index.tsx`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\index.ts`
- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\app\appRoutes.tsx`

- [x] **Step 1: Add `UserDeniedPage`**

Create:

```tsx
import type { CurrentUserResponse } from "@/lib/types/api/auth";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Link } from "react-router-dom";

export interface UserDeniedPageProps {
  missingPermissions?: string[];
  user?: CurrentUserResponse;
}

export default function UserDeniedPage({
  missingPermissions = [],
  user,
}: UserDeniedPageProps) {
  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Access denied</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          <div>
            <div className="font-medium">User</div>
            <div className="text-muted-foreground">
              {user?.username ?? "Unknown"}
            </div>
          </div>
          {missingPermissions.length > 0 && (
            <div>
              <div className="font-medium">Missing permissions</div>
              <ul className="mt-2 list-disc pl-5 text-muted-foreground">
                {missingPermissions.map((permission) => (
                  <li key={permission}>{permission}</li>
                ))}
              </ul>
            </div>
          )}
          <Button asChild size="sm">
            <Link to="/">Back to Home</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
```

- [x] **Step 2: Add `LoginRequiredPage`**

Create:

```tsx
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export interface LoginRequiredPageProps {
  message?: string;
}

export default function LoginRequiredPage({
  message = "Login required",
}: LoginRequiredPageProps) {
  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Login required</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          <p className="text-muted-foreground">{message}</p>
          <Button size="sm" onClick={() => window.location.reload()}>
            Refresh
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
```

- [x] **Step 3: Add `PermissionControlPage`**

Create one readable page file. Keep local helpers in the same file:

```tsx
import { useMemo, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Switch } from "@/components/ui/switch";
import { getApiErrorMessage } from "@/lib/api";
import {
  buildRoleAuthority,
  usePermissionConfig,
  useSecurityLevelRoles,
  useUpdatePermissionConfig,
} from "@/lib/query/auth";
import type {
  AppAuthConfig,
  PermissionCatalogItem,
  SecurityLevelRole,
} from "@/lib/types/api/auth";

export default function PermissionControlPage() {
  const configQuery = usePermissionConfig();
  const rolesQuery = useSecurityLevelRoles();
  const [updatePermissionConfig, updateState] = useUpdatePermissionConfig();
  const [selectedRole, setSelectedRole] = useState<SecurityLevelRole | null>(
    null,
  );
  const [draft, setDraft] = useState<AppAuthConfig | null>(null);

  const config = configQuery.data?.config;
  const catalog = configQuery.data?.permissionCatalog ?? [];
  const roles = rolesQuery.data?.roles ?? [];

  const configuredCounts = useMemo(() => {
    if (!config) {
      return new Map<string, number>();
    }
    return countPermissionsByRole(config, roles);
  }, [config, roles]);

  function openRole(role: SecurityLevelRole) {
    if (!config) {
      return;
    }
    setSelectedRole(role);
    setDraft(cloneConfig(config));
  }

  function closeDialog() {
    setSelectedRole(null);
    setDraft(null);
  }

  async function saveDraft() {
    if (!draft) {
      return;
    }
    try {
      await updatePermissionConfig(draft).unwrap();
      toast.success("Permission config saved");
      closeDialog();
    } catch (error) {
      toast.error(
        getApiErrorMessage(error, "Failed to save permission config", true),
      );
    }
  }

  if (configQuery.isLoading || rolesQuery.isLoading) {
    return (
      <div className="p-6 text-sm text-muted-foreground">
        Loading permissions...
      </div>
    );
  }

  if (configQuery.isError || rolesQuery.isError) {
    return (
      <div className="p-6 text-sm text-destructive">
        {getApiErrorMessage(
          configQuery.error ?? rolesQuery.error,
          "Failed to load permission config",
          true,
        )}
      </div>
    );
  }

  return (
    <div className="min-h-full space-y-4 p-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Permission Control</h1>
          <p className="text-sm text-muted-foreground">
            Configure module permissions for Security/Levels
            Authenticated/Roles.
          </p>
        </div>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            configQuery.refetch();
            rolesQuery.refetch();
          }}
        >
          Refresh
        </Button>
      </div>

      <div className="grid gap-3">
        {roles.map((role) => (
          <Card key={role.path}>
            <CardHeader className="flex flex-row items-center justify-between gap-3 space-y-0">
              <div className="min-w-0">
                <CardTitle className="truncate text-base">
                  {role.label || role.name}
                </CardTitle>
                <p className="truncate text-xs text-muted-foreground">
                  {role.path}
                </p>
              </div>
              <div className="flex shrink-0 items-center gap-3">
                <span className="text-xs text-muted-foreground">
                  {configuredCounts.get(role.path) ?? 0} enabled
                </span>
                <Button size="sm" onClick={() => openRole(role)}>
                  Configure
                </Button>
              </div>
            </CardHeader>
          </Card>
        ))}
      </div>

      <RolePermissionDialog
        role={selectedRole}
        catalog={catalog}
        draft={draft}
        saving={updateState.isLoading}
        onChange={setDraft}
        onClose={closeDialog}
        onSave={saveDraft}
      />
    </div>
  );
}

function RolePermissionDialog({
  role,
  catalog,
  draft,
  saving,
  onChange,
  onClose,
  onSave,
}: {
  role: SecurityLevelRole | null;
  catalog: PermissionCatalogItem[];
  draft: AppAuthConfig | null;
  saving: boolean;
  onChange: (config: AppAuthConfig) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  const authority = role ? buildRoleAuthority(role.path) : "";

  function toggle(permissionKey: string, enabled: boolean) {
    if (!draft || !role) {
      return;
    }
    const next = cloneConfig(draft);
    const current = new Set(next.permissions[permissionKey] ?? []);
    if (enabled) {
      current.add(authority);
    } else {
      current.delete(authority);
    }
    next.permissions[permissionKey] = Array.from(current).sort();
    onChange(next);
  }

  return (
    <Dialog open={!!role} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{role?.path ?? "Role permissions"}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          {catalog.map((permission) => {
            const checked =
              !!draft?.permissions?.[permission.key]?.includes(authority);
            return (
              <div
                key={permission.key}
                className="flex items-start justify-between gap-4 rounded-md border p-3"
              >
                <div className="min-w-0">
                  <div className="font-medium">{permission.label}</div>
                  <div className="text-sm text-muted-foreground">
                    {permission.description}
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    {permission.key}
                  </div>
                </div>
                <Switch
                  checked={checked}
                  onCheckedChange={(value) => toggle(permission.key, value)}
                />
              </div>
            );
          })}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={onSave} disabled={!draft || saving}>
            Save Changes
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function cloneConfig(config: AppAuthConfig): AppAuthConfig {
  return {
    ...config,
    superAdminRoles: [...config.superAdminRoles],
    superAdminSecurityLevels: [...config.superAdminSecurityLevels],
    permissions: Object.fromEntries(
      Object.entries(config.permissions).map(([key, values]) => [
        key,
        [...values],
      ]),
    ),
  };
}

function countPermissionsByRole(
  config: AppAuthConfig,
  roles: SecurityLevelRole[],
) {
  const counts = new Map<string, number>();
  for (const role of roles) {
    const authority = buildRoleAuthority(role.path);
    const count = Object.values(config.permissions).filter((values) =>
      values.includes(authority),
    ).length;
    counts.set(role.path, count);
  }
  return counts;
}
```

- [x] **Step 4: Export pages**

Update `pages/index.ts`:

```ts
export { default as HomePage } from "./HomePage";
export { default as LoginRequiredPage } from "./Auth/LoginRequiredPage";
export { default as UserDeniedPage } from "./Auth/UserDeniedPage";
export { default as PermissionControlPage } from "./Settings/PermissionControlPage";
```

- [x] **Step 5: Add Permission Control route**

Update imports in `appRoutes.tsx`:

```tsx
import { HomePage, PermissionControlPage } from "@/pages";
```

Add or replace the settings route with this child route:

```tsx
{
  id: "settings",
  path: "/settings",
  label: "Settings",
  breadcrumbLabel: "Settings",
  navGroupId: "guide",
  icon: Settings,
  children: [
    {
      id: "settings-permissions",
      path: "permissions",
      element: <PermissionControlPage />,
      label: "Permission Control",
      breadcrumbLabel: "Permission Control",
      icon: Settings,
      requiredPermissions: ["authorization.manage"],
    },
  ],
}
```

- [x] **Step 6: Verification command for execution phase**

Status: USER_CONFIRMED_VERIFICATION_DONE. User reported this verification step was completed.

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run lint
```

Expected: TypeScript passes.

---

### Task 7: Update Home dashboard without expanding scope

**Files:**

- Modify: `D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui\src\pages\HomePage\index.tsx`

- [x] **Step 1: Keep health card and add user summary**

Keep the existing health query. Add `useCurrentUser()` and show:

```tsx
const currentUser = useCurrentUser();
const permissionCount = currentUser.data
  ? Object.values(currentUser.data.permissions).filter(Boolean).length
  : 0;
```

Add a second compact card:

```tsx
<Card className="w-full max-w-sm">
  <CardHeader>
    <CardTitle>Current User</CardTitle>
    <CardDescription>
      Gateway or Perspective authenticated actor
    </CardDescription>
  </CardHeader>
  <CardContent className="space-y-2 text-sm">
    {currentUser.isLoading && (
      <div className="text-muted-foreground">Loading session...</div>
    )}
    {currentUser.isError && (
      <div className="text-destructive">
        {getApiErrorMessage(
          currentUser.error,
          "Unable to load current user",
          true,
        )}
      </div>
    )}
    {currentUser.data && (
      <>
        <div>
          <strong>Username:</strong> {currentUser.data.username}
        </div>
        <div>
          <strong>Source:</strong> {currentUser.data.source}
        </div>
        <div>
          <strong>Roles:</strong> {currentUser.data.roles.length}
        </div>
        <div>
          <strong>Module permissions:</strong> {permissionCount}
        </div>
      </>
    )}
  </CardContent>
</Card>
```

Use a simple responsive wrapper:

```tsx
<div className="grid min-h-full w-full gap-4 p-6 md:grid-cols-2">
  {/* existing health card */}
  {/* current user card */}
</div>
```

- [x] **Step 2: Do not redesign visual shell**

Keep existing cards, switches, buttons, and i18n usage. Avoid adding a new dashboard framework, chart library, or custom store.

Status: SOURCE_EDITED. Subagent spec review passed. Subagent code quality review passed. No build/test/lint/typecheck was run by Codex.

- [ ] **Step 3: Verification command for execution phase**

Status: NOT_RUN_BY_CODEX. User requested stopping after Task 7 document update for manual review.

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run lint
```

Expected: TypeScript passes.

---

### Task 8: Final verification and manual Gateway check

**Files:**

- No source file changes required in this task.

- [ ] **Step 1: Backend verification**

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
.\gradlew.bat --no-daemon --console plain :base:test :gateway:test
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Frontend verification**

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run lint
```

Expected:

```text
Done
```

or TypeScript exits with code `0`.

- [ ] **Step 3: Full module build**

Run only after user authorizes verification:

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
.\gradlew.bat --no-daemon --console plain build
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Manual Gateway verification**

In Ignition Gateway:

1. Install built `.modl`.
2. Open the module Gateway page.
3. Navigate to `Settings > Permission Control`.
4. Confirm the page lists roles from Security/Levels `Authenticated/Roles`.
5. Open one role.
6. Toggle `health.access`.
7. Save.
8. Refresh page.
9. Confirm the role still shows the permission enabled.
10. Log in as a user with that Security Levels role and confirm protected navigation matches configured permissions.

Expected: Role-based permission changes use Security Levels role paths.

## Self-Review

- Spec coverage:
  - Current user endpoint: Task 3.
  - Permission config endpoint: existing route plus Task 3 response alignment.
  - Security Levels roles endpoint: Task 2 and Task 3.
  - Frontend types/api/query: Task 4.
  - Route guard/sidebar filtering: Task 5.
  - Permission Control page: Task 6.
  - Home dashboard: Task 7.
  - Verification and manual Gateway check: Task 8.
- Placeholder scan:
  - No alternate role catalog implementation remains.
  - No generated UI role source uses anything except Security Levels role paths.
  - No broad refactor or new framework added.
- Type consistency:
  - Backend response uses `appAuthenticated` and `authorities`.
  - Frontend `CurrentUserResponse` matches backend response.
  - Role authority helper uses `role:<rolePath>` on both backend and frontend.
