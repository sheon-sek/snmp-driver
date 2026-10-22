# SNMP Driver

Ignition 8.3 Gateway 模块：通过 SNMP 轮询网络设备并接收设备主动上报，把结果暴露为 OPC UA 节点供 Ignition Tag 使用。

## Language

### 设备与轮询

**Device**:
Gateway 中配置的一台 SNMP 被管设备，是轮询与 Notification 接收的归属单位。
_Avoid_: Agent（指设备侧 SNMP 软件时才用）、target、host

### Notification

**Notification**:
设备主动发往 Gateway 的 SNMP 消息的统称，包括 Trap 与 Inform。
_Avoid_: event、alert、"trap"（泛指时）

**Trap**:
不需要 Gateway 确认的 Notification（v1 Trap、v2c/v3 Trap）。
_Avoid_: notification（特指时）

**Inform**:
需要 Gateway 回 ACK 的 Notification；Gateway 不 ACK 时发送方会重发。
_Avoid_: acknowledged trap

**Trap Listener**:
一个端口上的 Notification 接收端点，可被多个 Device 共享；由先启用的 Device 确定 bind 地址，bind 地址不同的 Device 不能共用同一端口。
_Avoid_: trap receiver、trap server

**Trap Status**:
Device 上 Trap 功能的状态：Disabled、Listening、Degraded（含 bind 失败与配置冲突）、Unlicensed。只影响 Trap，不影响轮询。
_Avoid_: listener health

**Unmatched Notification**:
源地址不属于任何 Device 的 Notification；只计数，不归属任何 Device。
_Avoid_: orphan trap

**Notification Snapshot**:
Device 上暴露的 Notification 状态：计数器加最近一条的内容，不是逐条事件记录。
_Avoid_: trap log、trap history
