# 用户手册 1.1.0 变更清单

本文列出用户手册 `docs/SNMP Driver - App&Project Engineer - v1.docx` 为 1.1.0 需要修改的内容，供人工更新 `.docx`（Q18）。本文不替代手册，`.docx` 本身不在此修改。

字段名、节点名以当前代码为准（`SNMPDeviceConfig`、`TrapDiagnostics`、`TrapItemPath`）。

> **回填占位**：标注 `【待 #41 回填】`、`【待 #42 回填】`、`【待 #43 回填】` 的段落，需在对应测试票关闭后，按 `docs/test-reports/1.1.0/` 中的报告结论替换。全部回填后才能关闭 #44。
>
> - #41：net-snmp 系统测试矩阵（含 Docker 端口映射源 IP 实测）
> - #42：升级、备份恢复与克隆测试（含 `notInTimeWindow` 实测现象）
> - #43：风暴测试（参考值与硬件条件）

## 0. 通用修改

| 位置 | 修改 |
|---|---|
| 封面 / 版本说明 | 版本改为 1.1.0 |
| 功能概述 | 增加 Trap/Inform 接收（v1/v2c/v3），与 `docs/SNMP_DRIVER_FEATURE_LIST.md` 一致 |
| “不支持的功能”一节 | 删除“Trap/Inform”；保留 MIB 导入/名称解析；新增“逐条 Trap 事件持久化（Event Streams / SQL）”“Gateway 级任意来源 Trap 接收”“冗余 Gateway”（见 §7） |
| 认证/隐私协议列表 | 核对为：Auth MD5、SHA1、SHA224、SHA256、SHA384、SHA512；Privacy DES、AES128、AES192、AES256 |
| 升级说明 | 1.0.0 的设备配置升级到 1.1.0 后无需修改即可加载；新增的 `trap*` 字段取默认值，`Trap Enabled` 默认关闭，轮询行为不变 |

## 1. 新增章节：Trap/Inform 配置

Device 配置新增 `TRAP` 分类，字段如下（表单 Label 以 Gateway 界面为准）：

| 字段（Label） | 配置名 | 默认 | 说明 |
|---|---|---|---|
| Trap Enabled | `trapEnabled` | 关闭 | 关闭时不占用端口 |
| Trap Bind Address | `trapBindAddress` | `0.0.0.0` | 只接受 IP 字面量；同一端口上第一个 Device 决定 listener 的地址 |
| Trap Port | `trapPort` | `162` | 见 §9 端口指引 |
| Trap Security | `trapSecurity` | `COMMUNITY` | `USM` 要求 SNMP Version 为 V3，并沿用 `V3_SECURITY` 分类的用户与口令 |
| Trap Community | `trapCommunity` | 空 | COMMUNITY 模式使用；为空时沿用 Read Community |
| Trap Sender Engine ID | `trapSenderEngineId` | 空 | 仅 V3；hex，5–32 字节；接收 v3 Trap 必填，见 §8 |
| Accept Inform | `trapAcceptInform` | 开启 | |
| Trap Source Allowlist | `trapSourceAllowlist` | 空 | 逗号分隔 IP/CIDR，追加 Device Host 之外的来源地址 |
| Trap Rate Limit per Second | `trapRateLimitPerSecond` | `100` | 1–10000，突发上限为 2 倍 |

需写明：

- 同一端口上的多个 Device 共享一个 Trap Listener，`Trap Bind Address` 必须一致；不一致的 Device `Traps/Status=Degraded`。
- 同一 listener 上相同 Security Name 的 v3 用户必须使用相同口令和协议，冲突的 Device 全部 Degraded，`LastError` 列出冲突设备名（运行期检查，不阻止保存）。
- 只发 Trap、不需要轮询的设备，也按普通 Device 配置，不建 Tag 即可。
- 来自未配置来源的 Notification 不归属任何 Device，只计入 `ListenerUnmatchedCount`；多个 Device 匹配同一来源时每个都收到（`ListenerMultiMatchCount`）。

## 2. 新增章节：Traps 节点与 TRAP.* 路径

`[Device]/Traps/` 节点：`Status`（`Disabled` / `Listening` / `Degraded` / `Unlicensed`）、`LastError`、`ListenerEndpoint`、`LocalEngineId`、`ReceivedCount`、`AcceptedCount`、`DroppedRateLimitCount`、`DroppedQueueFullCount`、`DroppedUnlicensedCount`、`AuthFailureCount`、`DuplicateInformCount`、`OverflowCount`、`ListenerUnmatchedCount`、`ListenerMultiMatchCount`。

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

实测现象：【待 #42 回填：真实 `.gwbk` 恢复（恢复 local / 不恢复 local 两种）后 `LocalEngineId` 与 boots 的变化、net-snmp 发送方的实际报错与恢复步骤；克隆后 `.cloned-<ts>` 与新 engineID 的实测结果】

## 11. License 行为

- License 无效时 Trap 端口保持打开；Trap 被丢弃并计入 `DroppedUnlicensedCount`，Inform 不 ACK，`Last/*` 不更新，`Traps/Status=Unlicensed`，`Last/*` 与 `TRAP.*` 质量为 `Bad_LicenseNotAvailable` 或 `Bad_LicenseExpired`。
- License 恢复后自动恢复接收，无需重启。

## 12. 容量与风暴参考值

模块常量：每个 listener 队列 10 000 条、1 个 worker；每条 Trap 最多 128 个 varbind；PDU 最大 65 535 B；Inform 去重 TTL 60 s。队列满时 Trap 计入 `DroppedQueueFullCount`，Inform 不 ACK。

参考值：【待 #43 回填：50 设备 × 25 OID、1 s 轮询，同时 2000 trap/s 持续 5 min 与 10000 trap/s 突发 30 s 下的轮询 p95 延迟增幅、超时、丢弃计数对账、堆内存表现，以及测试硬件条件（CPU、内存、OS、Docker 版本）】
