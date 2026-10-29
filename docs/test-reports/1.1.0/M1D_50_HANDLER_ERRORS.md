# #50 处理函数异常与配置错误交付

日期：2026-10-27。实现分支：`feat/50`，集成分支：`m1d/release`。实现基线与完成前合并检查：`m1d/release` 的 `7351fe6`。

## 实现

- 处理函数抛异常或返回 HANDLER_FAILED 时计入 `RecordHandlerErrorCount`，不重试，后续 Record 正常处理。
  日志逐条包含 Device、Sequence、Project、Function 和异常摘要，`Traps/LastError` 周期发布错误详情，成功调用不抹去最近错误。
- NOT_FOUND 计入 `RecordDroppedCount`，LastError 标识项目或函数；相同查找错误的日志去重保留。
- 捕获 Throwable，包括脚本深递归的 StackOverflowError 和 AssertionError；ThreadDeath 与其他 VirtualMachineError 继续抛出。
- 单次调用超过 5 秒，在该次调用结束时记录一次耗时警告；没有硬超时，不中断脚本。
- 新增 UInt64 只读节点 `RecordHandlerErrorCount`、`RecordDroppedCount`、`RecordQueueDepth`。
  QueueDepth 包含正在执行的一条，未配置处理函数时全为零；运行期间已处理/失败/找不到及积压可以对账。
- 保存配置要求 Project 和 Function 同时填写或同时留空，空白和 null 均按未填写处理；存在性仍在运行时检查。
- 保留 #48 的逐条获取 ScriptManager 和函数实现，不缓存脚本对象；系统用例检查下一条 Record 使用新版本。

## feat/50 实现阶段：TDD 与单元测试

按协调者确认的边界，用 SNMPDeviceFixture 的真实 Device、监听器、Trap 入站和 OPC 节点，替换 RecordHandlerInvoker；
配置测试走 ExtensionPoint.validate。异常计数、Error 继续、配置成对和慢调用告警均先观察失败再实现通过。

新增 JUnit：

- `handlerExceptionCountsAnErrorWithoutRetryAndNextRecordStillRuns`
- `scriptErrorsAreCountedAndLoggedWithSequenceAndDoNotStopLaterRecords`
- `slowCallWarnsOnceAfterReturningAndStillDeliversWithoutInterruption`
- `unavailableHandlerCountsDroppedAndReportsProjectAndFunctionThenRecovers`
- `recordHandlerProjectAndFunctionMustBeConfiguredTogether`

扩展现有顺序用例，验证首条阻塞时全部十条仍被接受，QueueDepth=10（包含 in-flight），Delivered=0；
扩展未配置用例，验证全部 Record 计数为零。

从 worktree 根目录运行 `bash ./gradlew :base:test :gateway:test --console=plain`：
base 13、gateway 316，全部通过，失败/错误/跳过均为 0。
最终合并检查后重新运行同一命令，BUILD SUCCESSFUL（1m57s），base 13、gateway 316，失败/错误/跳过均为 0；
JUnit XML 位于各模块 build/test-results/test（忽略的运行产物）。

## feat/50 实现阶段：系统用例与未覆盖项

编写但未执行：

- `record:handler-exception-continues`
- `record:missing-project`
- `record:missing-function`
- `record:disabled-project`
- `record:inheritable-project`
- `record:script-hot-update`

`python3 -m py_compile` 对 `system-test/cases.py` 和 `system-test/st.py` 通过。
热更新用例更改真实 Script Library 版本，不用运行时标签覆盖、不发预热包、不重启 Device/Gateway；
不可运行项目用例在 finally 恢复 fixture。每条用例检查终态对账。

feat/50 实现阶段遵守隔离约束，没有使用 igdev Gateway，没有执行六条系统用例及 system-test 全量回归；由 merger 串行验收。
队列容量、溢出、停止时丢弃、调度失败、跨 Device 压力等属于 #49/后续容量验收，本票不宣称覆盖；
#49 与本票共用 RecordDelivery、TrapDiagnostics、SNMPDevice 和接线测试，合并时需保留两方行为并重新验收。
未提交 credentials、Gateway 导出、.scratch、License bypass 或开发模块产物，未推送、未修改 issue。


## m1d/release 集成验收（2026-10-27）

合并提交：`693f24d`（父提交 `e8d8bcd`、`ee7f3b3`）。已解决四个文件的冲突，保留 #49 的有界 FIFO、容量配置、溢出丢新、停止丢弃、调度失败处理与同名 Device 调用串行化，同时接入 #50 的 HandlerError、NOT_FOUND 丢弃、LastError、Throwable 分类和慢调用日志。系统测试中 #49 的对账辅助函数也补入 HandlerError，采用完整四项对账式。

### 单元与构建

从 repo root 执行 `bash /home/sheon/snmp-driver/gradlew :base:test :gateway:test --console=plain`，BUILD SUCCESSFUL（1m50s）。JUnit XML 汇总：base 13、gateway 326，失败、错误、跳过均为 0；包含 #49 队列/生命周期测试及 #50 异常继续、StackOverflowError/AssertionError 计数和日志、5.1 秒调用仅警告一次、配置成对校验。

当前源码复制到 `.scratch/devbuild-50` 后，仅在副本应用 `.scratch/license-bypass.patch`；从 repo root 以 `-p` 指定副本构建，BUILD SUCCESSFUL（26s）。安装文件为 `com.jw.snmpdriver-1.1.0-m50-dev.unsigned.modl`，仅用于本地开发验收；正式源码、提交内容均无 bypass。

igdev 0.3.1 安装时首次 restart 遇到旧 m49 文件名挂载错误；模块已成功暂存，随后通过 `st.recreate_gateway` 重建容器以更新挂载，保留数据卷，再调用 `pin_gateway_identity`。安装后及测试清理后实测 MAC 均为 `02:42:ac:12:00:62`；Gateway 为 Ignition 8.3.10。

### 系统验收

定向 `python3 /home/sheon/snmp-driver/system-test/st.py run record`：11/11 通过，证据 `system-test/out/results-20261027T183349.json`（忽略的运行产物）。

| 用例 | 实测结果 |
|---|---|
| record:handler-exception-continues | 第一条计 HandlerError=1，sink 无该条；第二条正常且仅投递一次，序号为 2，完整对账成立 |
| record:missing-project / missing-function | 各 Dropped=1，LastError 标识错误项目/函数，sink 为空，对账成立 |
| record:disabled-project / inheritable-project | 不执行脚本，各 Dropped=1，LastError 标识不可运行原因，对账成立 |
| record:script-hot-update | 修改实际 Script Library 后，下一条用 v2，序号 1、2，无 Device/Gateway 重启、预热或重试 |
| record:thousand-fast | 1000 条 / 5.6 秒，1000 次投递，序号 1–1000 按序且连续 |
| record:overflow-drops-newest | 容量 100，150 条接受，保留 1–101，丢弃后到 49 条，终态 Delivered=101、Dropped=49、Depth=0 |
| record:ten-at-500ms / multi-device / unconfigured | 原有逐条内容、设备归属与未配置行为均通过 |

全量 `python3 /home/sheon/snmp-driver/system-test/st.py run`：60/60 通过，证据 `system-test/out/results-20261027T184629.json`（忽略的运行产物）。覆盖全部现有 v1/v2c/v3、路由、监听冲突、重放、Gateway 重启、模块重装、轮询、Docker UDP 映射、SET 和 11 条 Record 用例；本轮使用包含 HandlerError 的完整对账辅助函数。

本次没有执行 #47 后续容量票的 2000 trap/s × 5 分钟风暴测试，不宣称已完成整个 epic。

### 清理

全部 st-* 测试设备已删除，设备列表为空；恢复 st-records v1、normal，等待处理函数退出后清空 sink，实测 sink 为 []。Gateway ensure 与 OPC UA 连通检查成功，固定 MAC 仍为 `02:42:ac:12:00:62`。未提交 .scratch、bypass、开发模块、凭据或 Gateway 导出；未关闭 #50。
