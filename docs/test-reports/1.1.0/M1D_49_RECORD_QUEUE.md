# #49 Record 队列容量、溢出与对账验收

日期：2026-10-27。Gateway：Ignition 8.3.10，igdev，固定 MAC `02:42:ac:12:00:62`。

## 本次范围

Device 新增 `Record Queue Capacity`（默认 10000，范围 100–100000，保存时校验），Record 改为每 Device 有界 FIFO 队列；队列满时丢弃新到的 Record，已在队列中的保留。新增 `Traps/RecordDroppedCount`、`Traps/RecordQueueDepth`（含正在执行的一条）。对账式 `AcceptedCount = RecordDeliveredCount + RecordHandlerErrorCount + RecordDroppedCount + RecordQueueDepth` 成立；HandlerError 由 #50 补上，本票恒为 0。

同时处理 #48 审查转入项：

- 有界队列，满丢新并计 Dropped，`LastError` 写明容量及首个丢弃 sequence，日志去重；
- 线程池为 null 或 RejectedExecution 时，已入队 Record 计入 Dropped，`LastError` 与日志只报一次；
- 同一 Device 名共用调用锁，配置保存重建 Device 时新实例等旧实例正在执行的调用结束后再调用；
- stop/offer 在同一锁下判定，停止后入队的 Record 计 Dropped，不会被投递；停止时队列中未处理的 Record 计 Dropped 并立即刷新计数；
- `recordJson` 在 snapshot JSON 为空对象或非对象时仍输出合法 JSON。

## 合并

`feat/49`（5a50a2d）以 `git merge --no-ff` 合入 `m1d/release`，合并提交 33d35ce，无冲突，合并后无需集成修复。

## 单元测试

repo root 执行 `bash ./gradlew :base:test :gateway:test --console=plain`：BUILD SUCCESSFUL（1m48s）。JUnit XML：base 13、gateway 321，失败/错误/跳过均为 0。新增覆盖：

- `SNMPDeviceRecordDeliveryTest`：满队列丢新保留已排队；Device 停止时排队 Record 计 Dropped、正在执行的调用自然结束；对账式；
- `RecordDeliveryTest`（假调用者）：停止后 offer 计 Dropped 且不投递；线程池不可用/拒绝时计 Dropped 并报告；新实例等待旧实例调用；慢 Device 不阻塞其他 Device；
- `SNMPDeviceTrapConfigTest`：旧配置默认 10000；容量越界保存报 `recordQueueCapacity` 字段错误；
- `TrapStateTest`：空 snapshot 的 Record JSON 合法。

## 系统验收

构建：当前源码复制到 `.scratch/devbuild-49`，单独应用本地 License bypass patch，构建 `com.jw.snmpdriver-1.1.0-m49-dev.unsigned.modl` 安装；该产物未签名，仅供开发验收，不提交。igdev 更换模块文件名后首次 restart 再次遇到旧 mount 路径错误，`gateway ensure` 重建 compose 后 `pin_gateway_identity` 重新固定 MAC 恢复，数据卷保留。

定向 `python3 system-test/st.py run record`：5/5 通过（`system-test/out/results-20261027T181204.json`，忽略的运行产物）。

| 用例 | 结果 |
|---|---|
| record:thousand-fast | 1000 条 Trap 平均间隔 ≤ 10 ms（5.7 s 发完），处理函数调用 1000 次，sequence 1..1000 连续按序；Accepted=1000、Delivered=1000、Dropped=0、QueueDepth=0 |
| record:overflow-drops-newest | 容量 100 + 8 s 慢处理函数：执行中 1 条 + 排队 100 条，后到的 49 条丢弃；满时 Accepted=150、QueueDepth=101、Dropped=49，`LastError` 含“Record 队列已满”与容量；恢复后投递 1..101，最终 Delivered=101、Dropped=49、QueueDepth=0，对账式成立 |
| record:ten-at-500ms | 10 条间隔 500 ms → 调用 10 次，每条等于 Last/Json 加 device |
| record:multi-device | 同一 Notification 命中两个 Device，各投递一次 |
| record:unconfigured | 未配置函数的 Device 不产生 Record、计数为 0 |

全量 `python3 system-test/st.py run`：54/54 通过（`system-test/out/results-20261027T182706.json`）。包含全部现有 v1/v2c/v3、路由、监听冲突、重放、重启、模块生命周期、轮询、Docker UDP 映射、SET 及五条 Record 用例。

## 清理

测试结束后删除所有 st-* 测试设备（设备列表为空），st-records 恢复 normal 并清空 sink（0 条）；Gateway 运行中，模块 ACTIVE，MAC 实测仍为 `02:42:ac:12:00:62`。测试生成的 Jython 缓存移到忽略的 `.scratch`，未提交。

## 未覆盖

处理函数异常计数（HandlerError）、找不到项目/函数计 Dropped、慢调用 5 秒警告、脚本热更新及开启处理函数的风暴测试属于 #47 后续票。
