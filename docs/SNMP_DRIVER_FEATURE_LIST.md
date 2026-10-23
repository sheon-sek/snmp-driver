# SNMP Driver — Feature List

## 模块简介

SNMP Driver 是面向 Ignition 8.3 的原生 Device Driver Module。它将 SNMP 设备数据接入 Ignition OPC UA，供 Tag 监控、历史记录、报警及控制流程使用。

- **Module Name：** SNMP Driver
- **Current Version：** 1.1.0
- **Platform：** Ignition 8.3

## 核心功能

### 轮询与写入

- 原生集成 Ignition Device Connection 与 OPC UA 地址空间
- 支持 SNMP v1、v2c、v3
- 支持 UDP、IPv4、IPv6 及自动 IP Family 选择
- 支持 SNMP GET 轮询与 SNMP SET 写入
- 提供 Read Only 与 Read/Write 设备访问模式
- 支持 SNMPv3 `noAuthNoPriv`、`authNoPriv`、`authPriv`
- 身份验证协议：MD5、SHA-1、SHA-224、SHA-256、SHA-384、SHA-512
- 隐私协议：DES、AES-128、AES-192、AES-256
- 使用 Ignition Secret Management 保护 community 与 SNMPv3 credentials
- 通过 numeric OID Item Path 创建 OPC Tags；支持显式 SNMP 数据类型
- 根据 OPC UA subscription 自动启动、调整及停止 polling
- 合并相同 OID polling，并按 OID 数量及请求大小自动分批
- 可配置 timeout、retry、每次请求 OID 数量及设备并发请求上限
- 内置 circuit breaker；通信异常时保留最后可用值并更新 OPC UA quality
- 提供设备状态、活动 subscription/OID 数量、poll interval、最近成功及失败信息
- 将配置、通信、协议、安全及 License 问题映射为明确 OPC UA status code

### Trap / Inform 接收（1.1.0 新增）

- 接收 SNMP v1 Trap（按 RFC 3584 转换为 v2 形式）、v2c Trap、v2c Inform、v3 Trap、v3 Inform
- Inform 只在入队成功后 ACK；60 s 内重复的 Inform 不重复计数（`DuplicateInformCount`）
- Notification 归属已配置的 Device：按 Device Host 与 `Trap Source Allowlist`（IP/CIDR）匹配来源；未匹配的来源只计入 `ListenerUnmatchedCount`
- 同一端口上的多个 Device 共享一个 Trap Listener；端口绑定失败时只有 Trap 进入 `Degraded`，轮询不受影响，并每 30 s 自动重试
- 每 Device 节点 `[Device]/Traps/`：`Status`、`LastError`（含处置建议）、`ListenerEndpoint`、`LocalEngineId`、各类计数（Received / Accepted / DroppedRateLimit / DroppedQueueFull / DroppedUnlicensed / AuthFailure / DuplicateInform / Overflow / ListenerUnmatched / ListenerMultiMatch）
- 最近一条 Notification：`Traps/Last/*` 各字段与单值一致快照 `Traps/Last/Json`
- 按 trapOID 的动态 Tag 路径：`TRAP.Count/<oid>`、`TRAP.LastTime/<oid>`、`TRAP.LastVarbinds/<oid>`、`TRAP.Sequence/<oid>`（每 Device 最多跟踪 256 个 trapOID，无订阅时 240 个）
- v3 Trap 需配置发送方 engineID（`Trap Sender Engine ID`）；v3 Inform 使用持久化的 Gateway 本地 engineID，Gateway 克隆或换机时自动重新生成
- 每 Device 可配置限速（默认 100/s，burst 2×）；队列、PDU 大小、varbind 数量均有上限
- License 无效时端口保持打开，Trap 丢弃并计数、Inform 不 ACK；License 恢复后自动恢复

### License

- SNMP 操作（轮询、写入、Trap/Inform 接收）仅在有效 Module License 下运行
- 提供 Gateway License 页面，支持 License 查看、上传及重新验证

## 当前支持范围

- 通信模式：SNMP polling、GET、SET、Trap/Inform 接收
- OID 格式：numeric dotted notation（Trap 中的 OID 也以原始数字形式显示）
- 最低 polling interval：1 秒
- Trap 语义：Notification Snapshot（状态快照 + 计数），不是逐条事件队列
- 当前不包含：MIB 文件导入与 MIB 名称解析、逐条 Trap 事件持久化（Event Streams / SQL）、Gateway 级任意来源 Trap 接收、Walk/Table/Browse、多 varbind SET、冗余 Gateway 支持
