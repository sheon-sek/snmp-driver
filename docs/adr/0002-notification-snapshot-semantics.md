---
status: accepted
---

# B1 只暴露 Notification Snapshot，不提供逐条事件

Device 上的 Notification 以计数器加最近一条内容（`Traps/Last/*`、`TRAP.*/<trapOID>`）暴露，两次 Tag 采样之间的多条 Notification 只能通过计数看出次数，中间内容会丢失，也不保证一条 Trap 对应一条可确认报警。这样无需持久化层即可交付"有 Trap 到达即提醒"；逐条审计依赖后续的 Event Streams 或 SQL TrapStore。手册必须写明这一边界。

补记（2026-10-27）：逐条处理改由 ADR 0010（Notification Record 投递给项目脚本）提供，不依赖 Event Streams；本 ADR 的快照语义不变。
