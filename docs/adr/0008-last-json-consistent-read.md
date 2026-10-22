---
status: accepted
---

# 用单值 `Traps/Last/Json` 作为 Last 的一致读取契约

`Traps/Last/*` 是各自独立的节点，Read 与订阅都无法保证跨节点取到同一条 Notification；单纯的 Sequence 双读也不能保证一致。候选之一是让一次 OPC UA Read 只取一次不可变 Snapshot 引用，但它对订阅（Ignition Tag 的主要用法）没有保证，还要先验证能否介入 Read 请求层。因此新增单值节点 `Traps/Last/Json`：一个 JSON 对象，含 `sequence`、`receivedTime`、`pduType`、`version`、`trapOid`、`source`、`agentAddress`、`agentUpTime` 与 `varbinds` 数组，整体由一次写入发布，Read 与订阅都天然一致；`varbinds` 沿用 16 KiB 截断规则。现有 `Last/*` 单节点保留，手册写明它们是非原子的便利镜像，需要一致读取时读 `Last/Json`。

`TRAP.*/<trapOID>` 动态路径不另做每 OID 快照，文档写明它们是独立的计数器与最近一条；需要一致读取时用设备级 `Last/Json`。不用 `Snapshot` 作节点名，避免与 Notification Snapshot 术语混淆。该节点是新增的节点承诺，取代 `SNMP_DRIVER_V2_PLAN.md` §4.7 中"候选尚未批准"的表述。
