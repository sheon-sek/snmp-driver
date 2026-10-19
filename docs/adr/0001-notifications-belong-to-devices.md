---
status: accepted
---

# Notification 只归属已配置的 Device

B1 中 Trap Listener 按 UDP 源地址（Device host 解析结果加 `trapSourceAllowlist`）把 Notification 路由到已配置的 Device；匹配多个 Device 时广播给每一个，无匹配的只作为 Unmatched Notification 计数后丢弃。不提供 Gateway 级"接收任意来源"入口，以保持配置与 OPC 节点模型都挂在 Device 上；只发 Trap 的设备可配置为不带 Tag 的 Device。路由不使用 v1 PDU 的 agent-addr。

## Considered Options

- Gateway 级独立 Trap Receiver（方案 C）：可接收未配置来源，但需要新的配置资源和节点模型，推迟到路线图。
