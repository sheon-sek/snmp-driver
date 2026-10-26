# SNMP Driver

Ignition 8.3 SNMP Device Driver 项目。

当前版本：**1.1.0**。

- SNMP v1/v2c/v3 轮询（GET）与写入（SET），通过 numeric OID Item Path 创建 OPC Tag。
- 1.1.0 新增 Trap/Inform 接收（v1/v2c/v3），每个 Device 在 `Traps/` 下暴露状态、计数和最近一条 Notification，并提供 `TRAP.*` 动态路径。
- Module License 管理及 Gateway License 页面。

功能清单见 [docs/SNMP_DRIVER_FEATURE_LIST.md](docs/SNMP_DRIVER_FEATURE_LIST.md)；1.1.0 用户手册变更与 Trap 端口指引见 [docs/USER_MANUAL_1.1.0_CHANGES.md](docs/USER_MANUAL_1.1.0_CHANGES.md)。

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
- `gateway/`：Gateway hook、SNMP Device runtime（`gateway/device`）、SNMP 传输与轮询（`snmp/runtime`）、Trap/Inform 接收（`snmp/trap`）、License runtime、License routes、Gateway License 页面注册。
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

## SNMP Driver 运行时结构

运行时遵循 Ignition 8.3 Device API：

```text
AbstractDeviceModuleHook
└── SNMPDeviceExtensionPoint
    └── SNMPDevice
        ├── OPC UA address space（含 Traps/ 诊断节点）
        ├── subscription/polling
        ├── SNMP transport
        └── lifecycle/status
```

`SNMPDeviceExtensionPoint` 负责类型注册、typed settings、设备创建及保存时校验。`SNMPDevice` 负责地址空间与运行时生命周期。Trap Listener 由 Gateway 级 registry 按端口共享，绑定失败只让 Trap 功能 `Degraded`，不影响轮询。

## Trap/Inform 快速说明

- 在 Device 的 `TRAP` 分类中勾选 `Trap Enabled`，默认关闭；1.0.0 设备配置升级后无需修改，Trap 保持关闭。
- `Trap Port` 默认 162。Linux 建议配置 10162 并用 nftables 把 UDP 162 重定向过去；Windows 需停用系统 SNMP Trap 服务；Docker 必须固定 MAC 地址；`-p 162:10162/udp` 实测会把源 IP 改写为网关地址，Linux 优先用 host 网络加 nftables。详见 [端口指引](docs/USER_MANUAL_1.1.0_CHANGES.md#9-trap-端口指引)。
- v3 Trap 需要填写 `Trap Sender Engine ID`；v3 Inform 发送方使用 `Traps/LocalEngineId`。
- B1 暴露的是 Notification Snapshot（快照 + 计数），不是逐条事件队列；不支持冗余 Gateway。

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

- 单元测试与 SNMP4J 进程内集成测试：随代码维护（`./gradlew :gateway:test`）。
- igdev Gateway 冒烟（M1b/M1c）：已执行。
- 1.1.0 系统测试（net-snmp 互通、升级、备份恢复与克隆、风暴）：在 M1d 进行，报告位于 `docs/test-reports/1.1.0/`。
- Development `.modl` 未签名，不是生产 artifact；签名由人工完成。
