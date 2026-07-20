# SNMP Driver

Ignition 8.3 SNMP Device Driver 项目。

当前阶段已完成项目身份重命名、License 管理保留、示例功能清理。SNMP `DeviceExtensionPoint`、设备配置、OPC UA 节点、轮询及传输实现将在后续独立任务中加入。

## 项目标识

| 项目 | 值 |
|---|---|
| Gradle root project | `snmp-driver` |
| Module ID | `com.jw.snmpdriver` |
| Module name | `SNMP Driver` |
| Mount alias | `snmp-driver` |
| API base | `/data/snmp-driver/api/v1` |
| Java package | `com.jw.snmpdriver` |
| Frontend package | `snmp-driver` |

## 模块结构

- `base/`：通用 API、生命周期及 License 实现。
- `common/`：模块常量。
- `gateway/`：Gateway hook、License runtime、License routes、Gateway License 页面注册。
- `web-ui/`：License 页面 bundle。

Designer、Perspective component、webapp、widget、counter、health demo 已移除。

## 前端边界

`web-ui/src/packages` 只保留：

```text
packages/
└── page/
    ├── index.ts
    └── license/
        ├── index.ts
        ├── LicensePage.tsx
        └── LicensePage.module.css
```

唯一 embedded target：

- 文件：`page.js`
- library：`SnmpDriverPage`
- Gateway export：`SnmpDriverLicensePage`
- Gateway path：`/snmp-driver/license`

## License API

API base：

```text
/data/snmp-driver/api/v1
```

Routes：

```text
GET  /license/access
GET  /license/status
GET  /license/machine-request
POST /license/upload
POST /license/revalidate
```

License 管理 routes 使用 Ignition Gateway Web UI session access control。POST routes 同时要求 Ignition CSRF token。项目不维护额外用户、角色或权限配置。

## SNMP Driver 目标结构

后续运行时遵循 Ignition 8.3 Device API：

```text
AbstractDeviceModuleHook
└── SnmpDeviceExtensionPoint
    └── SnmpDevice
        ├── OPC UA address space
        ├── subscription/polling
        ├── SNMP transport
        └── lifecycle/status
```

`DeviceExtensionPoint` 负责类型注册、typed settings、设备创建及 health check。`Device` 负责地址空间与运行时生命周期。当前清理阶段不加入无法工作的占位设备。

## 常用命令

所有命令必须在 repo root 执行。

```powershell
.\gradlew.bat build
.\gradlew.bat :base:test
.\gradlew.bat :gateway:test
yarn --cwd web-ui install
yarn --cwd web-ui run lint
yarn --cwd web-ui run build
```

未获明确授权前，不执行 build、test、lint、typecheck、compile 或 package。

## 当前验证状态

- 静态改造：已完成。
- Build/test/lint：未运行。
- Ignition Gateway 安装及手动验证：未运行。
