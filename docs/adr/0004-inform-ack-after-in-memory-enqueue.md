---
status: accepted
---

# Inform 在进入内存队列后即 ACK

Gateway 不使用 SNMP4J `addNotificationListener`（它在处理前自动 ACK），而是在认证、路由、License、限速、去重、入队全部成功后才回 ACK；任一步失败不 ACK，由发送方重发。ACK 只表示"已进入内存队列"，进程崩溃时队列中的 Inform 会丢失，不保证持久化或 exactly-once。真正的先落盘再 ACK 依赖尚未实现的持久化层。

## M1b 补充（grilling，2026-10-22）

- **多设备命中**：一个 Inform 命中多个 Device 时，至少一个设备入队成功即 ACK；其余设备各自计自己的丢弃数。要求全部入队才 ACK 会让一台长期失败（如 Unlicensed）的设备拖住发送方无限重发。
- **去重**：键含设备，按设备去重。去重只在限速前**查询**，命中则再 ACK 一次，不扣令牌，计 `DuplicateInformCount`；**写入**必须在入队成功之后。入队前就写会把一次入队失败后的重发当成重复直接 ACK，使该 Inform 被确认却从未处理。
- 因此正文顺序列表中的"限速、去重"以此补充为准：去重查询 → 限速 → 入队 → 去重写入 → ACK。
