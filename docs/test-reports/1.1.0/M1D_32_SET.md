# M1d #32：Designer 写入 V2C Integer32 返回 Bad_GatewayCommOff

## 结论

`Bad_GatewayCommOff` 不是模块、Gateway 或设备返回的。它由 Designer 客户端在本地产生：Designer 的 Comm Mode 不是 Read/Write 时，写请求不发往 Gateway。模块的 SNMP SET 链路正常，未改生产代码。

## 根因证据

- 本仓库源码与模块运行日志中没有 `GatewayCommOff`。
- Ignition 8.3 SDK 中引用 `QualityCode.Bad_GatewayCommOff` 的类只有 `ClientTagManagerImpl`、`ClientTagSubscriptionManager`（client-api，Designer/Vision 客户端）、`QualityCode`/`DataQuality`（定义）以及 Perspective 绑定类。
- `ClientTagManagerImpl.writeAsync` 先调用 `GatewayConnection.getConnectionMode()`；模式不允许写入时调用 `failedReadWriteFuture`，后者为每个 TagPath 直接返回 `Bad_GatewayCommOff`（javap 反汇编 client-api-8.3.0.jar）。
- 同一设备形态在 igdev Gateway 上经 OPC UA 直接写入返回 Good，agent 值改变，轮询回读到新值（见下）。

## 处置

- 用户手册变更清单 §13：Designer Comm Mode 需切到 Read/Write；设备需 `accessMode=READ_WRITE` 并配置 `writeCommunity`。
- 新增回归测试，锁定写入链路。

## 测试

| 层 | 用例 | 结果 |
|---|---|---|
| JUnit | `SNMPDeviceWriteTest.v2cInteger32WriteSendsSetWithWriteCommunityAndReadsBack`：真实 `SNMPDevice` + 回环 SNMP4J v2c agent；写 42 → Good，agent 收到 community `st-private` 的 SET，订阅回读 42 | 通过 |
| JUnit | `SNMPDeviceWriteTest.readOnlyDeviceRejectsWriteWithoutSendingSet`：READ_ONLY → `Bad_NotWritable`，agent 未收到 SET | 通过 |
| 系统 | `set:v2c-integer32-write-readback`：snmpd `override -rw` Integer32，OPC UA 写 42 → Good，snmpget 5→42，轮询回读 42；改 READ_ONLY 后写入 → `BadUserAccessDenied`（OPC UA Server 按 AccessLevel 拦截），agent 值不变 | 通过 |
| 系统 | `set:v2c-integer32-write-readback-trap-on`：同上，设备开启 Trap；之后 v2c Trap 仍被接受 | 通过 |

说明：JUnit 用例从未在生产代码上失败过（调试中的失败均来自测试 agent 本身），因为生产代码没有缺陷，无法“先红后绿”；它作为写入链路的回归保护。Designer 本身的写入需人工在 Comm Mode = Read/Write 下复核。

## 全量回归

`python3 system-test/st.py run`：49/49 通过（results-20261026T171610.json，Ignition 8.3.10，dev 构建未变）。首轮 48/49：`module:reinstall` 的 glob 选中了 igdev 0.3.1 遗留的同名空目录 `*.modl`（环境问题，与本票无关）；用例已改为只取文件，重跑全过。`:base:test :gateway:test` 通过。
