---
status: accepted
---

# Inform 在进入内存队列后即 ACK

Gateway 不使用 SNMP4J `addNotificationListener`（它在处理前自动 ACK），而是在认证、路由、License、限速、去重、入队全部成功后才回 ACK；任一步失败不 ACK，由发送方重发。ACK 只表示"已进入内存队列"，进程崩溃时队列中的 Inform 会丢失，不保证持久化或 exactly-once。真正的先落盘再 ACK 依赖尚未实现的持久化层。
