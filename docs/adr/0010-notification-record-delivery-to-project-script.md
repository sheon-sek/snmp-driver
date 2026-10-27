---
status: accepted
---

# Notification Record 逐条投递给项目脚本

Notification Snapshot 经 Tag 采样（最短 1000 ms）后，突发时会丢失中间的 Notification：实测 10 条间隔 500 ms 的 Trap 只触发 6 次 Value Changed，无法满足"每条告警都要处理"。因此在快照之外新增 Record Delivery：每条被 Device 接受的 Notification 生成一条 Notification Record（`Last/Json` 结构加 `device` 字段的 JSON 字符串），由模块按到达顺序调用 Device 上配置的项目 Script Library 函数（`Record Handler Project` + `Record Handler Function`）各一次。模块不解析 Record，所有解析与业务处理都写在工程师的 Jython 函数里，模块保持通用。

- **不用 Event Streams**：官方 SDK 支持注册自定义 Event Stream 来源，体验最好，但 Event Streams 是需额外购买的模块，逐条处理不能依赖它。工程师仍可在自己的处理函数里调用 `system.eventstream.publishEvent` 转发。
- **模块调用（push）而非工程师来取（pull）**：工程师只写处理函数，不用自己维护已处理到哪一条；SDK 的 `ProjectManager.getProjectScriptManager` + `ScriptManager` 可直接调用项目脚本。
- **投递保证**：只保证 Gateway 进程运行期间不丢。每个 Device 一个有界队列（默认 10000），满了丢新到的；项目或函数找不到、Device 重启时队列中的 Record 都丢弃并计入 `RecordDroppedCount`；函数抛异常计入 `RecordHandlerErrorCount`，不重试，继续下一条；不设硬超时，用 `RecordQueueDepth` 与慢调用日志观察。对账关系：`AcceptedCount = RecordDeliveredCount + RecordHandlerErrorCount + RecordDroppedCount + RecordQueueDepth`。
- **Inform ACK 不变**（ADR 0004）：仍在进入内存队列后 ACK，不等脚本处理完，避免脚本慢或出错时发送方无限重发。
- 同一 Device 内按序、一次一条；不同 Device 之间并发，使用有界线程池。未配置处理函数的 Device 不产生 Record，行为与之前完全相同。
