# SNMP Driver License 集成说明

本文描述当前 SNMP Driver 的 License runtime、Gateway routes 与前端 License 页面接线。

## 身份

| 项目 | 值 |
|---|---|
| Module ID | `com.jw.snmpdriver` |
| Module name | `SNMP Driver` |
| Mount alias | `snmp-driver` |
| API base | `/data/snmp-driver/api/v1` |

## 后端组成

- `base/src/main/java/com/jw/ignition/license`：License verifier、file store、upload、audit、models。
- `base/src/main/java/com/jw/ignition/common/license`：`ModuleLicenseRuntime` 与 License requirement。
- `gateway/src/main/java/com/jw/snmpdriver/gateway/service/ServiceBundle.java`：License runtime 生命周期。
- `gateway/src/main/java/com/jw/snmpdriver/gateway/license/LicenseManagementRouteRegistrar.java`：License API 注册。
- `gateway/src/main/java/com/jw/snmpdriver/gateway/GatewayHook.java`：routes 与 Gateway License page 接线。

## Routes

```text
GET  /data/snmp-driver/api/v1/license/access
GET  /data/snmp-driver/api/v1/license/status
GET  /data/snmp-driver/api/v1/license/machine-request
POST /data/snmp-driver/api/v1/license/upload
POST /data/snmp-driver/api/v1/license/revalidate
```

GET routes 使用 Ignition `WebUiSessionAccessControl`。POST routes 使用 Ignition `CsrfWebUiSessionAccessControl`。前端从 `/data/app/session` 获取 CSRF token，并通过 `X-CSRF-Token` header 发送。

项目不保存额外用户、角色、权限 catalog 或 session identity。访问边界由 Ignition Gateway Web UI session 提供。

## 前端组成

```text
web-ui/src/packages/page/index.ts
web-ui/src/packages/page/license/index.ts
web-ui/src/packages/page/license/LicensePage.tsx
web-ui/src/packages/page/license/LicensePage.module.css
web-ui/src/lib/api/licenseClient.ts
web-ui/src/lib/types/api/license.ts
```

Bundle/export：

```text
page.js
SnmpDriverPage
SnmpDriverLicensePage
```

Gateway page：

```text
/snmp-driver/license
```

## 生命周期

1. `GatewayHook.setup(...)` 创建并 setup `ModuleLicenseRuntime`。
2. `GatewayHook.startup(...)` 启动 License runtime。
3. `mountRouteHandlers(...)` 注册 License routes。
4. `GatewayHook.shutdown()` 关闭 License runtime。

## 安全边界

- License management API 只接受 Ignition Gateway Web UI session。
- 写操作必须通过 CSRF access control。
- License upload 仍由现有 parser、verifier、file store 处理。
- `GatewayHook.isFreeModule()` 保持 `true`；项目使用自有 License runtime。
- License 页面不直接读取或显示 Gateway credentials。

## 修改检查

- `ModuleConstants.MODULE_ID` 与 License payload module ID 一致。
- `MOUNT_ALIAS` 与 API base、License storage path 一致。
- `SnmpDriverLicensePage` 与 Gateway mount export 完全一致。
- 前端 embedded API base 为 `/data/snmp-driver/api/v1`。
- SNMP runtime 加入后，业务能力通过 `ModuleLicenseRuntime` 检查，不绕过 License gate。

## 验证状态

当前文档只记录静态接线。Build、test、lint、Gateway 安装及浏览器行为需另行授权后验证。
