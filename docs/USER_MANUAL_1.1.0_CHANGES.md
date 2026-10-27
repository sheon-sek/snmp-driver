# 用户手册 1.1.0 变更清单

本文列出用户手册 `docs/SNMP Driver - App&Project Engineer - v1.docx` 为 1.1.0 需要修改的内容，供人工更新 `.docx`（Q18）。本文不替代手册，`.docx` 本身不在此修改。

字段名、节点名以当前代码为准（`SNMPDeviceConfig`、`TrapDiagnostics`、`TrapItemPath`）。

实测依据见 `docs/test-reports/1.1.0/`：

- `M1D_41_SYSTEM_TEST.md`：net-snmp 系统测试矩阵（含 Docker 端口映射源 IP、固定 MAC）
- `M1D_42_UPGRADE_RESTORE_CLONE.md`：升级、备份恢复与克隆（含 `notInTimeWindow`）
- `M1D_43_STORM.md`：风暴测试（参考值与硬件条件；含开启处理函数的重跑，#51）
- `M1D_48_RECORD_DELIVERY.md`、`M1D_49_RECORD_QUEUE.md`、`M1D_50_HANDLER_ERRORS.md`：逐条处理 Notification（Record Delivery）
- `M1D_32_SET.md`：SNMP SET 写入与 Designer `Bad_GatewayCommOff`

## 0. 通用修改

| 位置 | 修改 |
|---|---|
| 封面 / 版本说明 | 版本改为 1.1.0 |
| 功能概述 | 增加 Trap/Inform 接收（v1/v2c/v3），与 `docs/SNMP_DRIVER_FEATURE_LIST.md` 一致 |
| “不支持的功能”一节 | 删除“Trap/Inform”；保留 MIB 导入/名称解析；新增“模块内置的逐条 Trap 事件持久化（Event Streams / SQL）——需要时在处理函数中自行实现（见 §14）”“Gateway 级任意来源 Trap 接收”“冗余 Gateway”（见 §7） |
| 认证/隐私协议列表 | 核对为：Auth MD5、SHA1、SHA224、SHA256、SHA384、SHA512；Privacy DES、AES128、AES192、AES256 |
| 升级说明 | 1.0.0 的设备配置升级到 1.1.0 后无需修改即可加载；新增的 `trap*`、`recordHandler*`、`recordQueueCapacity` 字段取默认值，`Trap Enabled` 默认关闭、不配置处理函数，轮询行为不变 |

## 1. 新增章节：Trap/Inform 配置

Device 配置新增 `TRAP` 分类，字段如下（表单 Label 以 Gateway 界面为准）：

| 字段（Label） | 配置名 | 默认 | 说明 |
|---|---|---|---|
| Trap Enabled | `trapEnabled` | 关闭 | 关闭时不占用端口 |
| Trap Bind Address | `trapBindAddress` | `0.0.0.0` | 只接受 IP 字面量；同一端口上第一个 Device 决定 listener 的地址 |
| Trap Port | `trapPort` | `162` | 见 §9 端口指引 |
| Trap Security | `trapSecurity` | `COMMUNITY` | `USM` 要求 SNMP Version 为 V3，并沿用 `V3_SECURITY` 分类的用户与口令 |
| Trap Community | `trapCommunity` | 空 | COMMUNITY 模式使用；为空时沿用 Read Community |
| Trap Sender Engine ID | `trapSenderEngineId` | 空 | 仅 V3；hex，5–32 字节；接收 v3 Trap 必填，见 §8。可直接粘贴工具输出的格式（空格、冒号分隔或 `0x` 前缀）；只在 Trap 开启且 Trap Security 为 USM 时校验 |
| Accept Inform | `trapAcceptInform` | 开启 | |
| Trap Source Allowlist | `trapSourceAllowlist` | 空 | 逗号分隔 IP/CIDR，追加 Device Host 之外的来源地址 |
| Trap Rate Limit per Second | `trapRateLimitPerSecond` | `100` | 1–10000，突发上限为 2 倍 |

需写明：

- 同一端口上的多个 Device 共享一个 Trap Listener，`Trap Bind Address` 必须一致；不一致的 Device `Traps/Status=Degraded`。
- 同一 listener 上相同 Security Name 的 v3 用户必须使用相同口令和协议，冲突的 Device 全部 Degraded，`LastError` 列出冲突设备名（运行期检查，不阻止保存）。
- 只发 Trap、不需要轮询的设备，也按普通 Device 配置，不建 Tag 即可。
- 来自未配置来源的 Notification 不归属任何 Device，只计入 `ListenerUnmatchedCount`；多个 Device 匹配同一来源时每个都收到（`ListenerMultiMatchCount`）。

## 2. 新增章节：Traps 节点与 TRAP.* 路径

`[Device]/Traps/` 节点：`Status`（`Disabled` / `Listening` / `Degraded` / `Unlicensed`）、`LastError`、`ListenerEndpoint`、`LocalEngineId`、`ReceivedCount`、`AcceptedCount`、`DroppedRateLimitCount`、`DroppedQueueFullCount`、`DroppedUnlicensedCount`、`AuthFailureCount`、`DuplicateInformCount`、`OverflowCount`、`ListenerUnmatchedCount`、`ListenerMultiMatchCount`，以及 Record Delivery 的 `RecordDeliveredCount`、`RecordHandlerErrorCount`、`RecordDroppedCount`、`RecordQueueDepth`（见 §14.6）。

`[Device]/Traps/Last/`：`Sequence`、`ReceivedTime`、`PduType`、`Version`、`TrapOid`、`Source`、`AgentAddress`（仅 v1）、`AgentUpTime`、`Varbinds`（JSON，最大 16 KiB）、`Json`（单值一致快照）。

**语义边界（手册须原文写入，ADR 0002）**：模块暴露的是 Notification Snapshot（状态快照 + 计数器），不是逐条事件队列。两次 Tag 采样之间到达的多条 Notification，只有计数器能反映次数，`Last/*` 只保留最后一条，中间状态（例如 linkDown 紧接 linkUp）可能完全看不到。需要一致读取多个字段时读 `Last/Json`。报警应以 `Last/Sequence` 或 `TRAP.Count/<oid>` 的变化为触发依据，但不保证一条 Trap 对应一条报警。

动态 Tag 路径（只读，不触发 SNMP GET）：`TRAP.Count/<trapOID>`、`TRAP.LastTime/<trapOID>`、`TRAP.LastVarbinds/<trapOID>`、`TRAP.Sequence/<trapOID>`。

## 3. 240/256 槽位规则

- 每个 Device 最多跟踪 256 个不同的 trapOID。
- 其中 16 个槽位只留给已被 `TRAP.*` Tag 订阅的 trapOID；因此没有任何订阅时，最多自动跟踪 240 个。
- 已跟踪的 OID 不会被淘汰；超出上限的 trapOID 计入 `Traps/OverflowCount`，`Last/*` 仍会更新。
- 未被跟踪的 trapOID，其 `TRAP.Count/<oid>` 读到 `0`、质量为 Good。
- 建议：对关心的 trapOID 预先建立 `TRAP.*` Tag，以占用保留槽位。

## 4. noAuthNoPriv v3 的安全等级说明

- v3 noAuthNoPriv Trap 只有在 Device 的 `Security Level` 设为 `NO_AUTH_NO_PRIV` 时才接受；安全级别低于 Device 配置的报文被拒绝并计入 `AuthFailureCount`。
- noAuthNoPriv 没有认证和加密，安全等级等同 v2c community，不提供认证防重放保证。需要防伪造和防重放时使用 authNoPriv 或 authPriv。

## 5. 本地引擎身份、克隆处置

v3 Inform 以 Gateway 为权威方。本地 engineID 与 engineBoots 保存在：

```text
$data/config/local/com.jw.snmpdriver/trap-engine.properties
```

`Traps/LocalEngineId` 显示当前 engineID（hex）。模块每次启动 boots+1。

**克隆或换机**：文件中记录了机器指纹（哈希，不含明文）。启动时若判定为另一台机器，模块会：

1. 把旧文件改名为 `trap-engine.properties.cloned-<时间戳>` 保留；
2. 生成新的 engineID，boots 从 1 开始；
3. 记录 warn 日志并写入 `Traps/LastError`。

用户处置：

- 在所有 v3 Inform 发送方更新为新的 engineID（大多数发送方会自动 discovery）。
- **误判**时：先找出并恢复导致指纹变化的机器标识（例如 Docker 下重新固定 hostname/MAC，见 §6），再停止模块，用 `.cloned-<ts>` 文件覆盖 `trap-engine.properties`，最后重启模块。如果机器标识仍与旧文件不符，模块会再次判定为克隆并重新生成 engineID，恢复不会生效。
- **主动更换 engineID**：删除 `trap-engine.properties` 后重启模块。
- 采集不到任何机器标识时不判定克隆，保留现有 engineID。
- 影响范围仅 v3 Inform；v1/v2c/v3 Trap 不受影响。

## 6. Docker 下固定 hostname/MAC

容器内通常只能采集到 MAC 地址这一项机器标识（没有 DMI、磁盘序列号）。Docker 25 起，未指定 MAC 的容器**每次启动**（包括 `docker restart`，不只是重建）都会分配新的随机 MAC，模块随即判定为换机并重新生成 engineID（见 §5），v3 Inform 发送方需要重新配置，`engineBoots` 也不会递增（#41 实测：Docker 29.8.1）。因此 Docker 部署**必须**固定 MAC 地址，建议同时固定 hostname，例如：

```yaml
services:
  gateway:
    hostname: ignition-gw
    mac_address: "02:42:ac:11:00:02"
```

或 `docker run --hostname ignition-gw --mac-address 02:42:ac:11:00:02 ...`。固定后实测：Gateway 重启时 engineID 不变、`engineBoots` 每次加 1。Module License 使用同一组机器标识，不固定 MAC 时 License 同样会在每次容器启动后失配，所以这一步对 License 也是必需的。

## 7. 冗余 Gateway 不支持

1.1.0 不支持冗余 Gateway（Q3）。冗余对的两个节点都会监听 Trap 端口，行为未定义（例如重复计数、Inform 由两个节点各自 ACK）。手册在“限制”一节注明。

## 8. 查询 v3 trapSenderEngineId 的方法

接收 v3 Trap 必须在 Device 上配置发送方的 engineID（`Trap Sender Engine ID`，hex）。未配置时该 Device 只接收 v3 Inform，`LastError` 说明原因。设备 engineID 变化（换板、复位为出厂设置）后需要重新配置。

获取方法（任选一种）：

1. **从设备读取 `snmpEngineID.0`**（OID `1.3.6.1.6.3.10.2.1.1.0`），例如 net-snmp：

   ```sh
   snmpget -v 3 -u <user> -l authNoPriv -a SHA-256 -A <auth> <device-ip> 1.3.6.1.6.3.10.2.1.1.0
   ```

   返回的 Hex-STRING 去掉空格即为填写值。
2. **查看设备 Web/CLI 界面**中的 “SNMP Engine ID”。
3. **net-snmp 发送方**：发送 Trap 时用 `-e <hex>` 显式指定的值；未指定时为 snmpd 的 engineID（`snmpd.conf` 中的 `engineID` / `oldEngineID`）。
4. **从模块诊断**：配置有误时 `Traps/LastError` 提示“发送方 engineID 与 Trap Sender Engine ID 不一致”，并计入 `AuthFailureCount`。

注意不要与 `Traps/LocalEngineId` 混淆：后者是 Gateway 的 engineID，供 v3 **Inform** 发送方使用。

## 9. Trap 端口指引

`Trap Port` 默认 162。端口绑定失败时只有 Trap 功能 `Degraded`，轮询照常；`Traps/LastError` 给出原因与处置建议，模块每 30 s 自动重试绑定，端口释放后无需重启。

### 9.1 Linux（首推）

模块配置 `Trap Port = 10162`，由运维用 nftables 把 UDP 162 重定向到 10162：

```text
table inet snmptrap {
  chain prerouting { type nat hook prerouting priority dstnat; fib daddr type local udp dport 162 redirect to :10162 }
  chain output     { type nat hook output     priority -100;   fib daddr type local udp dport 162 redirect to :10162 }
}
```

`prerouting` 处理外部流量，`output` 处理本机发往 162 的流量。`fib daddr type local` 把重定向限定在发往本机地址的报文，本机发往其他 Trap 接收端的报文和经本机转发的流量不受影响。REDIRECT 保留源 IP，源地址路由与 allowlist 照常生效。不推荐对 JVM 使用 `setcap cap_net_bind_service`（权限作用于整个 JVM，且随 JRE 升级失效）。

### 9.2 Windows

1. 检查 `SNMPTRAP`（SNMP Trap）服务是否存在、是否运行，以及 UDP 162 被哪个进程占用：`Get-Service SNMPTRAP`、`netstat -ano -p udp | findstr :162`。
2. 由运维在授权后停止并禁用 SNMP Trap 服务。
3. 在 Windows 防火墙中放行 UDP 162 入站。
4. 无需重启 Gateway，模块会在 30 s 内自动重新绑定。

### 9.3 Docker

发布端口 `-p 162:10162/udp`（compose：`"162:10162/udp"`），模块配置 `Trap Port = 10162`，发送方发往宿主机 IP 的 162 端口。

**源 IP 是否保留**（#41 实测，Docker 29.8.1 rootful，默认 userland-proxy，WSL2 内核；报告见 `docs/test-reports/1.1.0/M1D_41_SYSTEM_TEST.md`）：

| 发送方 | 目的地址 | Gateway 看到的 `Last/Source` |
|---|---|---|
| 另一 Docker 网络中的容器 172.17.0.2 | 宿主机网桥地址 172.17.0.1:162 | 172.18.0.1（Gateway 所在网络的网关） |
| 宿主机本机 | 127.0.0.1:162 | 172.18.0.1 |

两种路径的源 IP 都被改写为 Gateway 所在 Docker 网络的网关地址。Notification 按源 IP 归属 Device，源 IP 被改写后会计入 `ListenerUnmatchedCount`，或者被错误地归属到 Host 恰好是网关地址的 Device；`Trap Source Allowlist` 不能恢复原始 IP。来自局域网外部主机、经物理网卡进入的流量在 WSL2 的 NAT 环境中无法测试，Linux 主机上 iptables DNAT 通常保留源 IP，但必须现场确认。

手册建议：

1. Linux 上优先使用 `network_mode: host`，按 §9.1 让模块监听 10162 并用 nftables 重定向 UDP 162，源 IP 保持不变。
2. 必须使用 `-p 162:10162/udp` 时，上线前从实际设备发一条测试 Trap，确认 `Traps/Last/Source`（或 `Last/Json.source`）是设备的真实 IP。如果显示的是网桥或网关地址，不要用 Device Host 去迁就这个地址，应改用 host 网络。
3. rootless Docker 的网络（slirp4netns/pasta）同样可能改写源 IP，并且宿主机 162 端口仍受低端口权限限制，不推荐用于接收 Trap。

### 9.4 LastError 文案

绑定失败时 `Traps/LastError` 形如：

```text
Trap listener bind <addr>:<port> failed: <OS 原因>.
Linux: set Trap Port to 10162 and redirect UDP 162→10162 with nftables;
Windows: stop/disable the "SNMP Trap" service;
Docker: publish -p 162:10162/udp and set Trap Port to 10162.
```

## 10. notInTimeWindow 处置

恢复旧备份（包括恢复 `config/local`）可能使 engineBoots 回退；模块不做猜测性补偿，只做 boots+1，也不会自动清除时间窗缓存来绕过防重放。

v3 Inform 发送方可能因此报 `notInTimeWindow`，Gateway 侧计入 `AuthFailureCount`，`LastError` 显示“v3 Inform 超出 Gateway 本地时间窗……发送方需重新 discovery 或清除其引擎缓存”。

处置：在发送方重新做 engineID discovery，或清除发送方对 Gateway 的引擎缓存（例如重启发送方的 SNMP 进程）。

实测现象（#42，Ignition 8.3.10，报告见 `docs/test-reports/1.1.0/M1D_42_UPGRADE_RESTORE_CLONE.md`）：

| 场景 | `LocalEngineId` | engineBoots | v3 Inform 发送方 |
|---|---|---|---|
| 同一台 Gateway 恢复 `.gwbk`，**不恢复 local**（`restoreLocal=false`） | 不变 | 继续 +1（5→6） | 正常，没有报错 |
| 同一台 Gateway 恢复 `.gwbk`，**恢复 local**（`restoreLocal=true`） | 不变 | 回退到备份值 +1（6→5，备份时为 4） | 仍持有旧 boots 的报文被拒绝：Gateway 回 notInTimeWindow Report，`AuthFailureCount` +1，`LastError` 如上；net-snmp 重新 discovery 后（新进程或清除引擎缓存）立即恢复 |
| 在另一台 Gateway 恢复同一份 `.gwbk` 并恢复 local（克隆） | 重新生成 | 从 1 开始 | 原 engineID 的配置在新机器上失效，需要按新的 `LocalEngineId` 重新配置；旧文件保留为 `trap-engine.properties.cloned-<ts>`，`LastError` 写明备份文件名和处置方法 |

建议：在同一台 Gateway 上做日常恢复时不勾选“恢复 local”，engineBoots 就能保持单调递增。只有整机迁移时才恢复 local，迁移后到新机器上核对 `LocalEngineId`。

## 11. License 行为

- License 无效时 Trap 端口保持打开；Trap 被丢弃并计入 `DroppedUnlicensedCount`，Inform 不 ACK，`Last/*` 不更新，`Traps/Status=Unlicensed`，`Last/*` 与 `TRAP.*` 质量为 `Bad_LicenseNotAvailable` 或 `Bad_LicenseExpired`。
- License 恢复后自动恢复接收，无需重启。

## 12. 容量与风暴参考值

模块常量：每个 listener 队列 10 000 条、1 个 worker；每条 Trap 最多 128 个 varbind；PDU 最大 65 535 B；Inform 去重 TTL 60 s。队列满时 Trap 计入 `DroppedQueueFullCount`，Inform 不 ACK。

参考值（本机单次测试，详见 `docs/test-reports/1.1.0/M1D_43_STORM.md`）：

- 测试条件：Intel Core Ultra 7 165U（14 逻辑核）、31 GiB 内存，Windows 11 + WSL2 Ubuntu 24.04，Docker 29.8.1，Ignition 8.3.10（最大堆 2 GiB）；发送方与 Gateway 在同一台机器上。
- 负载：50 台 v2c 设备 × 25 个 OID，1 s 轮询。
- 2000 trap/s 持续 5 min：轮询 p95 延迟 0.90 → 0.96 ms（+6.9%），无超时，Trap 全部接受。
- 10000 trap/s 突发 30 s：轮询 p95 延迟 0.90 → 1.35 ms（+49.9%，超过 20% 的目标），但无超时、无漏轮询，Trap 全部接受。增量绝对值仅 0.45 ms，且突发后无 Trap 时仍高 24.9%，主要是同机主机负载所致；负责人已接受该结果。
- 所有进入模块的 Notification 都能对上账：`ReceivedCount = AcceptedCount + DroppedRateLimitCount + DroppedQueueFullCount + DroppedUnlicensedCount + DuplicateInformCount`。
- 约 120000 trap/s 时队列会满：超出部分计入 `DroppedQueueFullCount`，Inform 不 ACK。更高的流量会在操作系统的 UDP 接收缓冲区被丢弃，这部分不出现在设备计数里，只能在系统层面（`netstat -su` 的 receive buffer errors）看到。
- 堆内存全程在 290–890 MiB 之间正常波动，没有上升趋势。

## 13. SNMP SET（写入）前提与 Designer 写入

写入 `RW.<Type>/<OID>` Tag 需同时满足：

- Device 的 `Access Mode` 为 `READ_WRITE`；v1/v2c 必须配置 `Write Community`（v3 沿用 V3 用户）。`READ_ONLY` 时写入被拒绝，不发 SNMP SET。
- Item Path 使用 `RW.` 前缀；不带前缀的路径只读。`TRAP.*` 路径与 `Traps/*` 节点始终只读。
- **Designer 写入**：Designer 的 Comm Mode 必须为 **Read/Write**。Comm Mode 为 Read-Only 或 Off 时，Designer 客户端在本地直接返回 `Bad_GatewayCommOff`，写请求不会发到 Gateway，也不会到达模块或设备。这不是模块或设备故障；把 Comm Mode 切到 Read/Write 后重试即可。
- 写入成功返回 Good，表示设备已对 SNMP SET 回应成功；Tag 的新值由下一次轮询回读。被拒绝时返回对应 Bad 状态（如 `Bad_NotWritable`、`Bad_UserAccessDenied`、`Bad_TypeMismatch`）。

## 14. 新增章节：逐条处理 Notification

依据：ADR 0010，实测见 `M1D_48_RECORD_DELIVERY.md`、`M1D_49_RECORD_QUEUE.md`、`M1D_50_HANDLER_ERRORS.md`、`M1D_43_STORM.md`（开启处理函数的重跑）。

### 14.1 何时用快照 Tag，何时用处理函数

| 需求 | 用法 |
|---|---|
| 看设备最近一条 Notification、计数趋势，按 `Last/Sequence` 或 `TRAP.Count/<oid>` 变化触发报警 | Notification Snapshot Tag（§2）即可 |
| 每一条 Notification 都要处理：写日志、入库、转发、生成工单 | 配置处理函数（Record Delivery） |

Tag 采样间隔最短 1000 ms，两次采样之间到达的多条 Notification 只有最后一条留在 `Last/*`。实测 10 条间隔 500 ms 的 Trap 只触发 6 次 Value Changed，因此 Tag 变化事件脚本不能用来逐条处理。处理函数由模块对每条被 Device 接受的 Notification 各调用一次，不经过 Tag 采样。

两者可以同时使用：配置处理函数不改变快照、计数器、Inform ACK 和轮询。未配置处理函数的 Device 不产生 Record，行为与以前相同。

### 14.2 配置字段

Device 配置 `TRAP` 分类下新增三个字段（Label 以 Gateway 界面为准）：

| 字段（Label） | 配置名 | 默认 | 说明 |
|---|---|---|---|
| Record Handler Project | `recordHandlerProject` | 空 | 处理函数所在的项目名。必须是已存在、已启用、非 inheritable 的项目 |
| Record Handler Function | `recordHandlerFunction` | 空 | 项目 Script Library（Project Library）中的函数，点分路径，至少两段，例如 `snmp.handlers.onNotification`。留空即关闭逐条处理 |
| Record Queue Capacity | `recordQueueCapacity` | `10000` | 100–100000。等待处理函数的 Record 条数上限，满了丢弃新到的 Record |

需写明：

- 保存时校验：Project 和 Function 必须同时填写或同时留空；Function 必须是合法的点分标识符路径；容量超出 100–100000 时报字段错误。项目和函数是否存在在运行时检查（见 §14.5），不阻止保存。
- 函数在 Gateway 中执行，可使用 Gateway 作用域的 `system.*` 函数，不能使用 Vision/Perspective 客户端函数。
- 每条 Record 都重新查找项目和函数，修改并保存 Script Library 后下一条 Record 就使用新代码，不需要重启 Device 或 Gateway。保存那一刻已经开始执行的调用仍按旧代码执行完。
- 保存 Device 配置会重建 Device：队列中尚未处理的 Record 丢弃并计入旧实例的 `RecordDroppedCount`，正在执行的那一条会执行完；新实例等它结束后才开始调用，同一 Device 不会出现两路并发调用。重建后 `Traps/*` 计数与 Record 序号从头开始。

### 14.3 函数签名、调用方式与 Record 内容

函数只有一个参数，类型是字符串（JSON），返回值被忽略：

```python
def onNotification(record):
    ...
```

Record 内容就是该 Device 的 `Last/Json` 在最前面加一个 `device` 字段，例如（v2c linkDown）：

```json
{"device":"core-sw-01","sequence":42,"receivedTime":"2026-10-27T08:15:02.481Z","pduType":"V2_TRAP",
 "version":"V2C","trapOid":"1.3.6.1.6.3.1.1.5.3","source":"10.0.1.5:42013","agentAddress":"","agentUpTime":12345,
 "varbinds":[{"oid":"1.3.6.1.2.1.1.3.0","type":"TimeTicks","value":"0:02:03.45"},
             {"oid":"1.3.6.1.6.3.1.1.4.1.0","type":"OBJECT IDENTIFIER","value":"1.3.6.1.6.3.1.1.5.3"},
             {"oid":"1.3.6.1.2.1.2.2.1.1.3","type":"Integer32","value":"3"}]}
```

字段含义与 `Last/*` 节点一致（§2）：`sequence` 为该 Device 内递增序号；`agentAddress` 仅 v1 有值；`varbinds` 中 `value` 一律为字符串，超过 16 KiB 时截断。模块只提供原始字段，不解析 MIB、不翻译 OID，业务含义由函数自行判断。

调用方式：

- 同一 Device 按到达顺序调用，一次一条，上一条返回后才调用下一条。
- 不同 Device 之间并发，全模块共用 4 个调用线程。函数应尽快返回；一个函数卡住会占住一个线程，并阻塞该 Device 后面的所有 Record。
- 一条 Notification 命中多个 Device（§1）时，每个 Device 各产生一条 Record，`device` 字段不同。

### 14.4 最小示例函数（仅供参考）

以下示例只演示用法，驱动不内置任何解析。在 Designer 中把函数写在项目的 Script Library 里（示例脚本名 `snmp/handlers`），然后在 Device 上填写 `Record Handler Project` 与 `Record Handler Function = snmp.handlers.onNotification`。

写 Gateway 日志：

```python
logger = system.util.getLogger("snmp.notifications")

def onNotification(record):
    r = system.util.jsonDecode(record)
    logger.info("device=%s seq=%s trapOid=%s source=%s" % (r["device"], r["sequence"], r["trapOid"], r["source"]))
```

写数据库（表结构、数据库连接名按现场修改）：

```python
def onNotification(record):
    r = system.util.jsonDecode(record)
    system.db.runPrepUpdate(
        "INSERT INTO snmp_notification (device, seq, received_time, trap_oid, source, payload) VALUES (?,?,?,?,?,?)",
        [r["device"], r["sequence"], r["receivedTime"], r["trapOid"], r["source"], record],
        "MyDatabase")
```

整条 `record` 原样存入 `payload` 列，以后需要的字段可以再从中取。函数抛出的异常（例如数据库不可用）会计入 `RecordHandlerErrorCount`，该条不重试（见 §14.5）；如需保证落库，在函数内自行捕获异常并写入备用位置。

### 14.5 投递保证与丢弃规则

**只保证 Gateway 进程运行期间**：Record 只在内存中排队，不持久化。Gateway 重启、模块重启、Device 重启（含保存配置、禁用 Device）时，队列中尚未处理的 Record 丢弃。

| 情况 | 结果 | 计数 |
|---|---|---|
| 函数正常返回 | 已投递 | `RecordDeliveredCount` +1 |
| 函数抛出异常 | 不重试，继续处理下一条；每条记一条 warn 日志（含 Device、Sequence、Project、Function 和异常摘要），写入 `Traps/LastError` | `RecordHandlerErrorCount` +1 |
| 队列已满（达到 `Record Queue Capacity`） | 丢弃新到的 Record，已在队列中的保留；`LastError` 写明容量和首个丢弃的 sequence | `RecordDroppedCount` +1 |
| 项目不存在、已禁用或为 inheritable；函数不存在、不是函数，或 Script Library 有语法错误 | 该条丢弃；`LastError` 指出项目或函数；同一错误日志只记一次。问题修复后下一条自动恢复 | `RecordDroppedCount` +1 |
| Device 停止或重建时队列中未处理的 Record | 丢弃 | `RecordDroppedCount` |
| Gateway 启动/停止期间投递线程不可用 | 丢弃 | `RecordDroppedCount` |

另外需写明：

- **没有硬超时**：模块不会中断正在执行的函数。单次调用超过 5 秒时，在该次调用结束后记一条 warn 日志 `Slow record handler call ... elapsedMillis=...`。慢函数的影响通过 `RecordQueueDepth` 和这条日志观察。
- **Inform ACK 不等函数**：Inform 在进入接收队列后即 ACK（与 1.1.0 其他行为一致），不等处理函数执行；函数慢或出错不会导致发送方重发。
- 只有被 Device 接受（计入 `AcceptedCount`）的 Notification 才产生 Record；被限速、接收队列满、Unlicensed、重复 Inform、认证失败等未被接受的 Notification 不产生 Record。
- `sequence` 出现断档说明中间有 Record 没有成功处理（丢弃或函数异常），可对照下面的计数排查。

### 14.6 Record 计数与对账

`[Device]/Traps/` 下新增四个只读节点（UInt64）：

| 节点 | 含义 |
|---|---|
| `RecordDeliveredCount` | 函数正常返回的次数 |
| `RecordHandlerErrorCount` | 函数抛出异常的次数 |
| `RecordDroppedCount` | 未调用或无法调用函数而丢弃的 Record 数（§14.5） |
| `RecordQueueDepth` | 当前排队的 Record 数，包含正在执行的那一条 |

未配置处理函数时四个节点都为 0。配置了处理函数的 Device，自本次启动（包括保存配置后的重建）起始终满足：

```text
AcceptedCount = RecordDeliveredCount + RecordHandlerErrorCount + RecordDroppedCount + RecordQueueDepth
```

建议对 `RecordQueueDepth` 持续偏高、`RecordDroppedCount` 或 `RecordHandlerErrorCount` 增长设置报警。

### 14.7 转发到 Event Streams

模块不直接对接 Event Streams（Event Streams 是需额外购买的模块，逐条处理不依赖它）。已有 Event Streams 的现场可以在处理函数中转发，例如发给项目 `alarms` 中来源为 Gateway Event Listener 的 Event Stream `snmp/notifications`：

```python
def onNotification(record):
    system.eventstream.publishEvent("alarms", "snmp/notifications", record, False)
```

`acknowledge=False` 时不等待 Event Stream 处理完即返回，避免拖慢后续 Record。

### 14.8 容量参考值

本机单次测试（硬件同 §12，详见 `docs/test-reports/1.1.0/M1D_43_STORM.md` 的 #51 一节）：50 台 v2c 设备 × 25 个 OID、1 s 轮询，全部设备配置处理函数，2000 trap/s 持续 5 min。

- Record 不丢：612 947 条全部投递，`AcceptedCount = RecordDeliveredCount + RecordHandlerErrorCount + RecordDroppedCount + RecordQueueDepth` 逐台成立。
- 处理函数写文件（每条一次文件追加）时处理速度约 1 900 条/s，略低于到达速度，结束时全部设备共积压约 3.5 万条，Trap 停止后约 2 分钟内处理完；没有队列满丢弃。
- 开启处理函数后轮询 p95 延迟升高（0.97 → 2.72 ms，+181%；即使函数什么都不做也升高 +262%），轮询节奏出现最长约 200 ms 的推迟，但无超时、无 Bad 值；约 0.3% 的 Notification 在操作系统 UDP 接收缓冲被丢弃（见 §12）。不配处理函数时同一环境无此现象。
- 驱动每处理一条 Record 约消耗 1.3 ms CPU（不含函数本身），2000 条/s 时约占 2.6 个核。

手册建议：

- 处理函数只做必要的轻量工作，耗时操作（远程调用、复杂计算）放到其他机制中异步完成。
- 逐条处理面向告警类 Notification；持续每秒上千条的流量下，按上述 CPU 消耗评估 Gateway 余量，并监控 `RecordQueueDepth`。
