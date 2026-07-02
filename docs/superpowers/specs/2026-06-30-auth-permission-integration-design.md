# Auth Permission Integration Design

## 背景

当前 boilerplate 后端已经提供标准 auth base control，前端也已经具备 RTK Query、route tree、layout/sidebar、Home Page 等基础结构，但 auth 与 permission 没有形成完整前后端闭环。

本设计目标是把 boilerplate 做成可直接复用的标准集成模板：

- 前端能读取当前登录用户与权限。
- 前端能按 route permission 控制导航与页面访问。
- 前端提供标准 Settings / Permission Control 页面。
- 权限配置以 Ignition Security/Levels 中 `Authenticated/Roles` 下的 roles 为主要对象。
- 后端与前端均只读取和配置 Security Levels roles；所有 role 选择以 Security Levels 中最终生效的 Roles 为准。

## 现状依据

- 后端 auth endpoint 已注册：
  - `GET /auth/me`
  - `GET /authorization/config`
  - `POST /authorization/config`
  - `POST /authorization/config/reset`
- 后端已有 permission catalog：
  - `authorization.manage`
  - `health.access`
- 前端 `web-ui/src/lib/api/index.ts` 已支持：
  - `credentials: "include"`
  - Perspective headers：`X-EEAUTH-Session-Id`、`X-EEAUTH-Project`
  - 标准 `ApiResponse<T>` unwrap
  - 标准 `ApiErrorResponse` normalize
- 前端 `web-ui/src/app/appRoutes.tsx` 已预留 `requiredPermissions?: string[]`，但目前没有执行权限控制。

## 核心设计决策

采用 Security Levels role authority 格式：

```text
role:<rolePath>
```

原因：

- Ignition 最终权限判断应以 Security/Levels 中 `Authenticated/Roles` 下的 role 为准。
- Security Levels 是权限配置的唯一 role 目录。
- 前端 Settings workflow 应直接列出 `Authenticated/Roles` 下所有 role。
- 后端 `/auth/me` 与 `/authorization/config` 必须使用同一 authority 格式，否则保存后的权限无法正确应用。

保留未来扩展格式：

```text
securityLevel:<path>
claim:<name>
```

本轮只要求 roles workflow 完整闭环。

## 后端 API 设计

### Current User

`GET /auth/me`

返回当前 actor 与 computed permissions。

响应 data：

```ts
interface CurrentUserResponse {
  source: string;
  appAuthenticated: boolean;
  username: string;
  roles: string[];
  securityLevels: string[];
  authorities: string[];
  permissions: Record<string, boolean>;
}
```

要求：

- Gateway Web UI 与 Perspective session 均返回同一 shape。
- `roles` 表示当前用户在 Ignition Security/Levels `Authenticated/Roles` 下生效的 role path。
- `authorities` 中 roles 使用 `role:<rolePath>`。
- permission check 使用 Security Levels roles 构造 authorities，不依赖其他角色目录。

### Permission Config

`GET /authorization/config`

返回当前配置与 module permission catalog。

响应 data：

```ts
interface PermissionConfigResponse {
  config: AppAuthConfig;
  permissionCatalog: PermissionCatalogItem[];
}

interface AppAuthConfig {
  version: number;
  revision: number;
  superAdminRoles: string[];
  superAdminSecurityLevels: string[];
  permissions: Record<string, string[]>;
}

interface PermissionCatalogItem {
  key: string;
  label: string;
  description: string;
}
```

要求：

- `permissions[permissionKey]` 的 values 使用 Security Levels role authorities。
- POST 保存必须使用 `revision` 做乐观并发控制。
- revision 冲突时返回标准 API error，前端提示刷新，不静默覆盖。

### Security Level Roles

新增：

`GET /authorization/security-level-roles`

权限要求：

```text
authorization.manage
```

响应 data：

```ts
interface SecurityLevelRolesResponse {
  roles: SecurityLevelRole[];
}

interface SecurityLevelRole {
  path: string;
  name: string;
  label: string;
}
```

要求：

- 后端通过 Ignition Security Levels API 读取 `Authenticated/Roles` 下所有 roles。
- `path` 是保存到 authority 的 canonical role path，必须稳定且与 permission check 使用的 role path 一致。
- roles 排序稳定；优先按 path 排序。
- 读取 Security Levels roles 失败时返回可诊断错误；不要静默当作空 roles。

## 后端 Auth Model 调整

### AppAuthContext

承载当前 actor 在 Security Levels 中生效的 roles。

要求：

- `roles()` 返回 Security/Levels `Authenticated/Roles` 下的 canonical role path。
- `authorities()` 生成 `role:<rolePath>` authorities。
- role authority 构造逻辑集中在一个 helper，避免前后端手写格式分叉。

### Current Actor Role Resolution

role 权限生效的前提是后端能为当前 actor 解析 Security Levels 中最终生效的 roles。

要求：

- Gateway Web UI request 必须从 Ignition Web UI session / auth context 中解析 Security Levels roles。
- Perspective request 必须从 Perspective session 的 authenticated user context 中解析 Security Levels roles。
- `/auth/me` 返回的 `roles` 与后端 permission check 使用同一 role resolution 结果。
- 如果 Security Levels roles 无法解析，后端返回标准 401/403 或可诊断 500，不使用其他角色目录作为替代配置对象。

### AppAuthConfig

继续使用：

```java
Map<String, Set<String>> permissions
```

但 normalize/validate 允许：

```text
role:<rolePath>
securityLevel:<path>
claim:<name>
```

`role:<rolePath>` 中的 role path 必须来自 Security/Levels `Authenticated/Roles`。

迁移策略：

- 本轮不做自动历史迁移。
- 如果旧配置中存在无法匹配 Security Levels role path 的 role authority，normalize 可保留或丢弃需在实现计划中明确；推荐标记为 unknown authority 并禁止新 UI 生成。

## 前端文件结构

必须遵守当前 web-ui structure。

新增/修改：

```text
web-ui/src/lib/types/api/auth.ts
web-ui/src/lib/api/auth.ts
web-ui/src/lib/query/auth.ts
web-ui/src/components/auth/RouteGuard.tsx
web-ui/src/pages/Auth/UserDeniedPage/index.tsx
web-ui/src/pages/Auth/LoginRequiredPage/index.tsx
web-ui/src/pages/Settings/PermissionControlPage/index.tsx
web-ui/src/pages/index.ts
web-ui/src/app/appRoutes.tsx
```

约束：

- 后端 response type 放在 `lib/types/api`。
- RTK endpoint 放在 `lib/api`。
- 页面消费 hook 放在 `lib/query`。
- 页面统一放在 `pages/<Domain>/<Page>/index.tsx` 或既有 `pages/<Page>/index.tsx` 模式下的清晰 hierarchy。
- 不在 page 内直接写 fetch。

## 前端 Query/API 设计

`lib/api/auth.ts`：

- `currentUser`
- `permissionConfig`
- `updatePermissionConfig`
- `resetPermissionConfig`
- `securityLevelRoles`

`lib/query/auth.ts`：

- `useCurrentUser`
- `usePermissionConfig`
- `useSecurityLevelRoles`
- `useUpdatePermissionConfig`
- `useResetPermissionConfig`
- `hasPermission(user, permissionKey)`
- `buildRoleAuthority(rolePath)`

要求：

- `buildRoleAuthority` 与后端格式一致：`role:<rolePath>`。
- mutation 成功后 invalidates/refetch config 与 current user。
- 401/403 走标准 error UI。

## Route Guard 设计

`requiredPermissions` 从 `appRoutes` 读取。

行为：

- user loading：显示紧凑 loading state。
- 401 `UNAUTHENTICATED`：显示 `LoginRequiredPage`。
- 403 或缺权限：显示 `UserDeniedPage`。
- 有权限：渲染目标 page。

导航：

- sidebar 默认隐藏当前用户无权访问的 nav item。
- 直接输入 URL 访问无权限 route 时，不 redirect，显示 denied page。
- Home Page 可保持不要求权限，作为模块入口和状态页。

## Settings / Permission Control 页面设计

页面 route：

```text
/settings/permissions
```

权限：

```text
authorization.manage
```

页面布局：

- 顶部：标题、说明、刷新按钮、保存状态。
- 主体：Security Levels `Authenticated/Roles` role list。
- 列出所有可配置 roles。
- 每个 role 行显示：
  - role name
  - role path
  - 已启用 permission 数量
  - `Configure` 按钮
- `Configure` 打开 dialog。

Dialog：

- 标题：`<rolePath>`
- 内容动态渲染 `permissionCatalog`。
- 每个 permission 显示：
  - label
  - description
  - key
  - switch
- switch toggle 只更新本地 draft。
- `Save Changes` 提交当前完整 config。
- `Cancel` 丢弃本次 dialog draft。

保存规则：

- 以 `config.revision` 提交。
- 保存成功后关闭 dialog，刷新 config/current user。
- revision conflict：保持 dialog 打开，提示配置已变更，用户需 reload 后再改。
- 其他 API error：显示标准错误信息。

## Home Page 设计

Home Page 从单纯 health demo 调整为 module dashboard：

- Gateway connection / module version。
- 当前用户摘要：
  - username
  - source
  - roles count
- 权限摘要：
  - 当前用户拥有多少 module permissions。
- 快捷入口：
  - Permission Control，仅在有 `authorization.manage` 时显示。

Health demo 保留，但变成 dashboard 的一个状态区域。

## Fallback 页面设计

### UserDeniedPage

用于：

- authenticated 但缺权限。
- 直接访问无权 route。

内容：

- 标题：Access denied
- 显示缺失 permissions。
- 显示当前 username/source。
- 提供返回 Home 按钮。

### LoginRequiredPage

用于：

- 401 `UNAUTHENTICATED`。
- Gateway/Perspective session 无法解析。

内容：

- 标题：Login required
- 显示后端 error message。
- 提供刷新按钮。

## 错误处理

必须区分：

- 401：未登录或 session 无效。
- 403：已登录但权限不足。
- 409 或 revision conflict：配置被其他用户更新。
- 500：后端异常。

前端不把所有错误都显示为 disconnected。

## 非目标

本设计不包含：

- 新建/编辑 Ignition roles。
- 用户管理。
- security level 管理 UI。
- claim 管理 UI。
- 自动迁移旧权限配置。
- 大规模视觉 redesign。

## 验收标准

- 前端能显示当前登录用户与 computed permissions。
- Settings 页面能从后端读取 Security/Levels `Authenticated/Roles` 下所有 roles。
- 用户能按 `Role -> Permissions` 配置权限。
- 保存后，后端配置中出现 `role:<rolePath>` authorities。
- route `requiredPermissions` 生效。
- sidebar 对无权限 item 做隐藏。
- 直接访问无权限 route 显示 denied fallback。
- 401 显示 login required fallback。
- 所有新增 API response type 位于 `lib/types/api`。
- 所有 RTK endpoints 位于 `lib/api`。
- 所有页面位于 `pages` hierarchy。
