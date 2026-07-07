# Ignition SDK Boilerplate

Ignition 8.3 module 开发用 boilerplate。它不是空模板，而是已经带有 Gateway Web UI、Perspective component、统一 API 响应、共享 auth/permission 框架、React shell、route-tree 导航、Shadow DOM 样式隔离、RTK Query API 层和 shadcn/ui 组件集的起点。

当前代码中有历史拼写 `ignition-sdk-boilerplate`。这是 repo truth，已用于 mount alias、API base、导航路径、auth namespace、package name 等位置。重命名项目时必须统一处理，不能只改显示名。

## Tech Stack

### Ignition / Backend

- Ignition SDK `8.3.0^`
- Java `17`
- Gradle wrapper
- `io.ia.sdk.modl` Gradle plugin `0.1.1`
- Module scopes: `base`, `common`, `gateway`, `designer`, `web-ui`
- Gateway route framework: Ignition `RouteGroup`
- JSON: Ignition Gson
- Persistence: Gateway PersistentRecord / SimpleORM
- Tests: JUnit Jupiter `5.10.3`

### Frontend

- Node `>=20`
- Gradle-managed Node `20.12.2`
- Yarn `1.22.18`
- npm `10.5.0`
- React `18.2.0`
- TypeScript `~5.8.2`
- Vite `^6.2.0`
- Tailwind CSS `3`
- shadcn/ui generated components, pinned by team convention to `shadcn@3.8.5`
- Radix UI primitives
- lucide-react icons
- Redux Toolkit + RTK Query
- React Redux
- React Router DOM `7.14.1`
- i18next + react-i18next
- sonner toast
- vaul drawer
- Perspective client package `@inductiveautomation/perspective-client@2.1.16`

shadcn 注意事项：新增组件使用 `shadcn@3.8.5` 生成，保持 React 18 兼容。生成后的组件是源码，位于 `web-ui/src/components/ui`，不是运行时黑盒依赖。

## Project Structure

```text
ignition-sdk-boilerplate/
  build.gradle
  settings.gradle
  gradle.properties
  base/
  common/
  gateway/
  designer/
  web-ui/
  docs/superpowers/
```

### `base`

共享框架层。当前包含：

- `com.jw.ignition.common.api`
  - `AbstractApiRouteRegistrar`
  - `ApiErrorHandler`
  - `ApiException`
  - `ApiResponses`
- `com.jw.ignition.common.util`
  - `Json`
- `com.jw.ignition.auth`
  - auth namespace
  - auth route registrar
  - auth filter
  - auth core
  - auth config persistence

原则：跨 module 可复用、业务无关的机制放这里。Gateway module 自己的 permission catalog、页面、业务 route 不放这里。

### `common`

Ignition Gateway / Designer / Perspective 共用常量和 component descriptor。

关键文件：

- `ModuleConstants.java`
- `PerspectiveComponents.java`
- `component/ExampleComponent.java`
- `resources/example-component.props.json`

当前模块 ID：

```text
com.jw.ignitionsdkboilerplate
```

当前 mount alias / nav path 使用历史拼写：

```text
ignition-sdk-boilerplate
```

### `gateway`

Gateway scope。负责薄 orchestration：

- `GatewayHook`
  - 初始化 `AuthCore`
  - 注册 Gateway nav page
  - 注册 Perspective component
  - mount API route handlers
  - 暴露 mounted resource folder
- `api/ApiRouteRegistrar`
  - module-specific routes
- `api/HealthRoutes`
  - health endpoint
- `auth/AppPermission`
  - module permission catalog

原则：`gateway` 不复制共享 auth/helper 逻辑。共享机制放 `base`，`gateway` 只连线。

### `designer`

Designer scope。当前只负责在 Designer 中注册 Perspective component descriptor。

### `web-ui`

React + Vite frontend。它同时服务两个入口：

- Gateway navigation page
- Perspective example component

关键目录：

```text
web-ui/src/app
web-ui/src/components/auth
web-ui/src/components/common
web-ui/src/components/foundation
web-ui/src/components/layout
web-ui/src/components/ui
web-ui/src/i18n
web-ui/src/lib/api
web-ui/src/lib/query
web-ui/src/lib/types
web-ui/src/pages
web-ui/src/store
```

## Main Runtime Surfaces

### Gateway Page

Gateway hook 注册 SystemJS module：

```text
/res/ignition-sdk-boilerplate/ignitionSdkBoilerplate.js
```

Gateway nav path：

```text
/ignition-sdk-boilerplate
```

前端入口：

```text
web-ui/src/index.tsx
```

Vite config：

```text
web-ui/vite.config.ts
```

### Perspective Component

Component ID：

```text
com.jw.ignitionsdkboilerplate.example
```

前端入口：

```text
web-ui/src/perspective-entry.tsx
```

Vite config：

```text
web-ui/vite.config.perspective.ts
```

Perspective props schema：

```text
apiBase
locale
theme
```

默认 API base：

```text
/data/ignition-sdk-boilerplate/api/v1
```

## API Contract

所有 module API 通过 `ModuleConstants.API_PREFIX` 挂载：

```text
/api/v1
```

Ignition data route 完整 URL 形态：

```text
/data/ignition-sdk-boilerplate/api/v1/<route>
```

当前 routes：

```text
GET  /health
GET  /auth/me
GET  /authorization/config
POST /authorization/config
POST /authorization/config/reset
GET  /authorization/security-level-roles
```

成功响应：

```json
{
  "success": true,
  "data": {}
}
```

错误响应：

```json
{
  "status": 403,
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Access denied."
  },
  "timestamp": "2026-07-02T00:00:00Z"
}
```

前端 `baseApi` 会 unwrap `success/data`，并把非标准错误 normalize 成 `ApiErrorResponse`。

## Custom Mechanisms

### 1. Shared API Route Registrar

`AbstractApiRouteRegistrar` 提供统一 route mount helper：

- `json(...)`
- `binary(...)`
- permission guard
- error wrapper
- JSON renderer

新增 Gateway API 时优先：

1. 在 `gateway/src/main/java/.../api` 新增 route class。
2. 在 `ApiRouteRegistrar` 注册 path。
3. 返回 `ApiResponses.ok(...)` 或抛出 `ApiException`。
4. 前端在 `web-ui/src/lib/types/api` 定义 response type。
5. 前端在 `web-ui/src/lib/api` 注入 RTK endpoint。
6. 前端在 `web-ui/src/lib/query` 暴露页面用 hook。

### 2. Auth / Permission Framework

核心位置：

```text
base/src/main/java/com/jw/ignition/auth
```

核心对象：

- `AuthCore`
- `AppAuthOptions`
- `AppAuthContext`
- `AppAuthConfig`
- `AuthFilter`
- `AuthRoutes`
- `AuthRouteRegistrar`
- `AppAuthConfigRecord`

权限模型：

```text
permission key -> allowed authorities
```

role authority 格式：

```text
role:Authenticated/Roles/<RoleName>
```

保留扩展格式：

```text
securityLevel:<path>
claim:<name>
```

当前主流程只围绕 Ignition Security Levels：

```text
Security/Levels/Authenticated/Roles
```

默认 super-admin role：

```text
Authenticated/Roles/Administrator
```

module permission catalog 当前在：

```text
gateway/src/main/java/com/jw/ignitionsdkboilerplate/gateway/auth/AppPermission.java
```

当前 permission keys：

```text
authorization.manage
health.access
```

### 3. Gateway Web UI + Perspective Identity

Gateway Web UI：

- 优先从 `WebUiSession.WebUiSessionAccessControl.getWebAuthUser(...)` 解析用户。
- 角色映射到 canonical Security Levels role path。
- Principal fallback 只保留 authenticated username，不虚构 roles。

Perspective：

- 前端从 Perspective client context 读取 session/project。
- 请求头传给 Gateway：

```text
X-EEAUTH-Session-Id
X-EEAUTH-Project
```

- 后端用 Perspective session monitor 找到对应 session。
- authenticated user roles 再映射到 canonical Security Levels role path。

### 4. Frontend Dynamic API Base

`web-ui/src/embedContext.ts` 持有 runtime context：

- embed mode: `standalone` / `perspective`
- Perspective session context
- API base

`WebApp` 接收：

```ts
apiBase?: string
locale?: string
theme?: string
```

`baseApi` 每次请求用 `getApiBase()` 拼接 URL。

### 5. Route Tree Drives Routes + Navigation

主配置：

```text
web-ui/src/app/appRoutes.tsx
```

它同时生成：

- `flatAppRoutes`
- `navigationGroups`

新增页面时优先改 `appRoutes`，不要并行维护多个 route/nav 配置。

route 可配置：

```ts
requiredPermissions?: string[]
showInNav?: boolean
children?: AppRouteDefinition[]
```

`RouteGuard` 按 `requiredPermissions` 控制页面访问。

sidebar 会递归过滤当前用户无权限访问的 navigation item。

### 6. React Shell

核心 shell：

- `AppProvider`
  - Shadow DOM boundary
  - Redux provider
  - Theme provider
  - Toaster
- `DynamicRouter`
  - Perspective: `MemoryRouter`
  - Gateway page / dev: `HashRouter`
- `AppLayout`
  - sidebar state
  - app content
  - auth loading/error 下隐藏 sidebar content
- `AppContentTopPanel`
  - sidebar trigger
  - breadcrumb
  - language/theme switchers
- `AppSidebarFooter`
  - health status hover card

### 7. Shadow DOM Styling Boundary

`ShadowBoundary` 把 app 渲染进 shadow root：

- 注入 `src/global.css`
- 注入 sonner styles
- 注入 vaul styles
- 创建 portal root
- 抽取 Tailwind `@property --tw-*`
- 抽取 font-face 规则到 document head

目的：降低 Gateway / Perspective 宿主页面 CSS 冲突。

### 8. Theme / Language Persistence

Redux slice：

```text
web-ui/src/store/uiPreferencesSlice.ts
```

localStorage key：

```text
app-state-storage
```

保存：

- theme mode: `system | dark | light`
- language
- sidebar collapsed

Theme provider 会把 `data-theme`、`class`、`lang` 写到 shadow app root。

支持语言：

```text
en-US
zh-CN
zh-TW
ko-KR
de-DE
es-ES
```

### 9. Vite Dual-Bundle Build

`web-ui/package.json`：

```text
yarn run build
```

实际执行：

```text
vite build --mode embedded
vite build --mode embedded --config vite.config.perspective.ts
```

输出目录：

```text
web-ui/build/generated-resources/mounted
```

Gateway bundle：

```text
ignitionSdkBoilerplate.js
```

Perspective bundle：

```text
ignitionSdkBoilerplatePerspective.js
```

`vite.inline-css.ts` 会把 Vite 输出 CSS inline 到 UMD chunk，并从 Rollup bundle 中移除 CSS asset。它不删除磁盘文件。

## Quick Start

所有命令必须从 repo root 运行。

```powershell
Set-Location -LiteralPath "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate"
```

### 1. Install frontend dependencies

```powershell
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" install
```

### 2. Start frontend dev server

```powershell
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run dev
```

Default dev URL:

```text
http://localhost:9999
```

当前 `.env.dev` 中 `VITE_API_BASE_URL` 指向：

```text
/data/ignition-sdk-template/api/v1
```

如本 repo 独立运行，应检查是否需要改为：

```text
/data/ignition-sdk-boilerplate/api/v1
```

### 3. Build module

需要构建时运行：

```powershell
.\gradlew.bat --no-daemon --console plain clean build
```

Gradle 会先构建 `web-ui`，再打包 module。

当前 module fileName 配置：

```text
com.jw.ignitionsdkboilerplate-1.0.7-beta13.modl
```

`skipModlSigning` 为 `true`，因此这是开发用未签名模块，不是生产签名 artifact。

### 4. Backend tests

```powershell
.\gradlew.bat --no-daemon --console plain :base:test
.\gradlew.bat --no-daemon --console plain :gateway:test
```

### 5. Frontend typecheck

```powershell
yarn --cwd "D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate\web-ui" run lint
```

这里的 `lint` 是 TypeScript `tsc --noEmit`，不是 ESLint。

## Common Development Tasks

### Add a Gateway API route

1. 在 `gateway/src/main/java/com/jw/ignitionsdkboilerplate/gateway/api` 新增 route class。
2. 在 `ApiRouteRegistrar.register(...)` 中挂载。
3. 如需权限，在 `AppPermission.catalog()` 新增 permission key。
4. 在前端 `web-ui/src/lib/types/api` 新增 response/request type。
5. 在 `web-ui/src/lib/api` 新增 RTK endpoint。
6. 在 `web-ui/src/lib/query` 新增 hook/helper。
7. 页面只调用 query hook，不直接 `fetch`。

### Add a secured page

1. 在 `web-ui/src/pages/<Domain>/<Page>/index.tsx` 新增页面。
2. 在 `web-ui/src/pages/index.ts` export。
3. 在 `web-ui/src/app/appRoutes.tsx` 新增 route。
4. 用 `requiredPermissions` 声明权限。
5. 确认 i18n key 加到所有 locale JSON。

示例：

```tsx
{
  id: "settings-permissions",
  path: "permissions",
  element: <PermissionControlPage />,
  label: "Permission Control",
  requiredPermissions: ["authorization.manage"],
}
```

### Add a module permission

1. 在 `AppPermission.java` 增加 key。
2. 加到 `catalog()`。
3. 如页面需要保护，在 route `requiredPermissions` 中引用同一个 key。
4. 如页面需要展示文案，在 i18n 中添加 translation。
5. Permission Control page 会从后端 catalog 自动显示新 permission。

### Add shadcn/ui component

使用 team convention 版本：

```powershell
npx shadcn@3.8.5 add <component>
```

生成后检查：

- 是否写入 `web-ui/src/components/ui`
- import alias 是否使用 `@/`
- 是否兼容 React 18
- 是否符合当前 Tailwind v3 token
- 是否需要 portal container 支持 Shadow DOM

### Add Perspective props

1. 修改 `common/src/main/resources/example-component.props.json`。
2. 修改 `web-ui/src/perspective-entry.tsx` 的 `ExampleComponentProps`。
3. 修改 `getPropsReducer(...)`。
4. 必要时传入 `WebApp`。
5. 如影响 API base/session/theme/language，检查 `embedContext.ts` 和 `ThemeProvider.tsx`。

## Rename Checklist

重命名整个项目不能只改 `moduleName`。至少检查以下位置。

### Gradle / Module metadata

- `gradle.properties`
  - `rootProjectName`
  - `moduleId`
  - `moduleName`
  - `moduleDescription`
  - `moduleVersion`
- `settings.gradle`
  - root project name 来源
- `build.gradle`
  - hook class package
  - module fileName
  - project scopes

### Java packages and constants

- package path:
  - `com.jw.ignitionsdkboilerplate.common`
  - `com.jw.ignitionsdkboilerplate.gateway`
  - `com.jw.ignitionsdkboilerplate.designer`
- `ModuleConstants.java`
  - `MODULE_ID`
  - `MODULE_NAME`
  - `MODULE_WEBAPP_GW_FILENAME`
  - `MODULE_WEBAPP_GW_COMPONENT_NAME`
  - `MODULE_WEBAPP_GW_NAV_CAREGORY_ID`
  - `MODULE_WEBAPP_GW_NAV_PATH`
  - `MODULE_WEBAPP_GW_NAV_PAGENAME`
  - `MODULE_WEBAPP_P_FILENAME`
  - `MODULE_WEBAPP_P_COMPONENT_CATEGORY`
  - `MOUNT_ALIAS`
  - `GATEWAY_NAV_CATEGORY_ID`
- `AuthConstants.java`
  - `AUTH_NAMESPACE`
- `ExampleComponent.java`
  - `COMPONENT_ID`
  - `COMPONENT_NAME`
  - default meta name
- `PerspectiveComponents.java`
  - browser resource ID

### Frontend package and bundle names

- `web-ui/package.json`
  - `name`
- `web-ui/.env.embedded`
  - `APP_GW_ENTRY_FILENAME`
  - `APP_GW_LIB_NAME`
  - `APP_GW_LIB_ENTRY`
  - `APP_P_ENTRY_FILENAME`
  - `APP_P_LIB_NAME`
  - `APP_P_LIB_ENTRY`
  - `VITE_API_BASE_URL`
  - `VITE_PERSPECTIVE_COMPONENT_TYPE`
- `web-ui/.env.dev`
  - `VITE_API_BASE_URL`
- `web-ui/index.html`
  - demo component attributes
- `web-ui/src/perspective-entry.tsx`
  - component type env usage
- `web-ui/src/i18n/locales/*/common.json`
  - app title and copy

### URLs and IDs

Current historical values to replace consistently:

```text
ignition-sdk-boilerplate
ignitionSdkBoilerplate
ignitionSdkBoilerplatePerspective
com.jw.ignitionsdkboilerplate
com.jw.ignitionsdkboilerplate.example
Ignition SDK Boilerplate
```

Also search typo-correct spelling separately:

```text
ignition-sdk-boilerplate
```

## Development Principles

- Keep `base` generic and reusable.
- Keep `gateway` thin.
- Keep module-specific permission keys in `gateway/auth/AppPermission.java`.
- Keep frontend API types, endpoints, and hooks split across `lib/types/api`, `lib/api`, and `lib/query`.
- Keep route/navigation in `appRoutes.tsx` as single source of truth.
- Do not directly fetch from page components.
- Do not add another state library; use Redux Toolkit for shared app state and RTK Query for server state.
- Do not bypass `RouteGuard` for production routes.
- Do not invent alternate role catalogs; use Ignition Security Levels `Authenticated/Roles`.
- Keep Perspective and Gateway render paths using the same `WebApp` where possible.
- Preserve Shadow DOM styling boundary unless there is a concrete host-integration reason to change it.
- Do not run build/test/lint unless explicitly requested.

## Known Current Notes

- Historical spelling `bolierplate` is still active in route/package/env values.
- `.env.dev` currently points API base at `ignition-sdk-template`, while embedded mode points at `ignition-sdk-boilerplate`.
- `RouteGuard` bypasses auth checks in Vite dev mode through `import.meta.env.DEV`.
- `lint` means TypeScript validation only.
- Build output is development unsigned `.modl` because signing is skipped.
