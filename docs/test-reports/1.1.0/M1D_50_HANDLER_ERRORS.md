# #50 处理函数异常与配置错误交付

日期：2026-10-27。分支：`feat/50`。实现基线与完成前合并检查：`m1d/release` 的 `7351fe6`。

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

## TDD 与单元测试

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

## 系统用例与未覆盖项

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

本任务遵守隔离约束，没有使用 igdev Gateway，没有执行六条系统用例及 system-test 全量回归；由 merger 串行验收。
队列容量、溢出、停止时丢弃、调度失败、跨 Device 压力等属于 #49/后续容量验收，本票不宣称覆盖；
#49 与本票共用 RecordDelivery、TrapDiagnostics、SNMPDevice 和接线测试，合并时需保留两方行为并重新验收。
未提交 credentials、Gateway 导出、.scratch、License bypass 或开发模块产物，未推送、未修改 issue。
