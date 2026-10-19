# M0 最终评审修复与验证

日期：2026-10-19。工作树 `/home/sheon/orca/workspaces/snmp-driver/m0-integration`，分支 `m0/integration`，修复基线 `47251c0ff0e9c1a9bb1718da27a74c62a308960e`。本轮由一名实现者完成，未派生 worker。

## 依据与范围

完整读取 `/tmp/snmp-m0-review-spec.md` 与 `/tmp/snmp-m0-review-standards.md`，将其作为待核对的评审证据。规则来自 `/home/sheon/Projects/snmp-driver/AGENTS.md`、该主仓库 `docs/agents/domain.md` 与 `docs/agents/issue-tracker.md`；工作树缺少 `docs/agents`。另读取当前 [CONTEXT](../../CONTEXT.md)、[ADR 0003](../adr/0003-v3-trap-requires-sender-engine-id.md)、[M0 规格](M0_IMPLEMENTATION_SPEC.md)、[M0-S 调查](M0S_CONTRACT_FINDINGS.md)，以及实时 [#4](https://github.com/sheon-sek/snmp-driver/issues/4)、[#11](https://github.com/sheon-sek/snmp-driver/issues/11)、[#12](https://github.com/sheon-sek/snmp-driver/issues/12) 的正文、标签和评论。

> 🧠 **From Hindsight memory (M0 polling reliability implementation)** — 历史页记录 M0 以 #4 与 #5–#12 为范围，主测试边界为注入的 SnmpRequestClient 与 scheduler，v3 行为可补局部进程内边界。

上述约束均重新对照实时 #4 和当前源码。该页仍保留早期“子任务尚未开始”的历史状态，另一个历史页称 gateway 无测试；当前基线已含实现和测试，故不采用这些旧状态。已向当前项目 session bank 写入状态校正文档，没有提升 approved knowledge。项目与个人 approved-knowledge 检索均无相关结果；提交前再次检索项目历史与知识页。

按已确认的测试边界和已授权 Gradle 范围实施。遵守指定 TDD 技能，两个行为缺陷分别先 red 后 green；不增加配置、依赖、兼容层或新的生产测试接口。

## 两项行为修复

### Spec 1：超时后迟到的 discovery REPORT

当前 SNMP4J 3.12.2 源码表明，`Snmp.PendingRequest.run()` 在超时通知前释放 MPv3 状态；`MPv3.prepareDataElements()` 找不到 msgID 时创建新的安全状态，并在完整响应匹配前学习 engineID。此前 `extractScopedPDU()` 只拒绝带旧 endpoint 的状态，放过无 endpoint 的新状态。

修复位于 [Snmp4jRequestClient](../../gateway/src/main/java/com/jw/snmpdriver/snmp/runtime/Snmp4jRequestClient.java) 的 `KeyAwareMPv3.extractScopedPDU()`：仅允许已关联当前 endpoint 的请求状态继续进入 USM 与 MPv3 处理。超时释放后无归属的状态、旧 generation 的状态均先拒绝。保留 `prepareDataElements()` 原有 MPv3/keys 临界区，避免 generation 激活插入检查与引擎写入之间；未改内部合法重试或实际引擎选键流程。本 polling client 不承担 Notification 接收。

[Snmp4jKeyCacheTest](../../gateway/src/test/java/com/jw/snmpdriver/snmp/runtime/Snmp4jKeyCacheTest.java) 新增 `discoveryReportAfterTimeoutCannotReplaceTheNewGenerationEngineOrKeys`。测试 Device 实际编码并暂存引擎 A 的匿名 noAuth discovery REPORT，等待旧请求超时，再替换为引擎 B、建立新 generation 并完成 GET，最后通过原 socket 发回 A 的旧报文。等待接收分发结束后检查引擎提示仍为 B，再通过公开 GET 验证成功且真实派生次数不增加。观察器在分发开始前捕获等待对象，避免上一响应唤醒测试线程后误完成下一报文的等待。没有用 sleep 代替接收完成。

外部证据为 `/tmp/m0s-research/src/snmp4j-3.12.2-sources/org/snmp4j/` 中 `Snmp.java` 超时路径、`mp/MPv3.java` 的 msgID 查找/引擎写入与 `security/USM.java` 匿名 noAuth 路径。先执行 Context7 library 解析与 docs 检索，无配额错误；其索引为旧版本，仅作导航，结论采用本机固定 3.12.2 源码及真实进程内报文回归。

### Spec 2：转换失败值及缓存重发的时间戳

修复位于 [SubscriptionBridge](../../gateway/src/main/java/com/jw/snmpdriver/gateway/device/SubscriptionBridge.java) 的 `SnmpValueException` 分支。失败值继续使用原异常的 statusCode 和空值，同时写入 `sourceTime=raw.receivedAt` 与发布时的 `serverTime`。codec 的异常契约、失败分类及正常转换入口保持原样。

[SubscriptionBridgeTimestampTest](../../gateway/src/test/java/com/jw/snmpdriver/snmp/runtime/SubscriptionBridgeTimestampTest.java) 通过真实 SharedPollCoordinator、SubscriptionBridge、SnmpValueCodec 和 DeviceDiagnostics，配合既有 fake client/可控 scheduler，订阅 Integer32 并返回合法 OctetString。只在外部 DeviceContext/DataItem 接口使用替身。首次失败发布和协调器实际缓存重发均断言 `Bad_TypeMismatch`、固定接收时间和各次发布的 serverTime 范围；同时检查 latest 缓存读取不发送 GET。第二次 GET 尚未返回，因此第二次发布确实来自缓存。没有替换动态发布所有权。

## Standards 全部编号处置

| 编号 | 处置 | 当前证据与理由 |
| --- | --- | --- |
| 1，构造器与回调池所有权 | 已修复 | SharedPollCoordinator 仅保留生产三参构造器和 package-private 四参时钟注入构造器。删除隐式创建池的重载和 ownsCallbacks 标志，统一由调用方关闭 client/scheduler/callbacks，并在公共构造器注明。ServiceBundle 原有关闭顺序为 runtime.stop 后关闭 callbacks；异步测试显式创建并关闭池，同步测试注入的执行器不创建线程。 |
| 2，DnsResolver 无调用重载 | 已移除 | 删除前以全仓 git grep 检索 DnsResolver、resolve 调用及方法引用，并核对 main/test 全部引用。两个生产调用均为 resolve(endpoint, ipv6Available)，其余 resolver.resolve 属于 AddressResolver，没有单参 DNS 调用；无仓外兼容要求。 |
| 3，双份 generation | 已修复 | 移除 SNMPDevice.statusGeneration 及镜像赋值。read/write 和本地失败入口通过 DeviceStatusMachine.currentGeneration() 取得受同一锁保护的 generation；已捕获 generation 的异步回调仍使用原捕获值，旧代际拒绝规则未改。 |
| 4，Security 检查 | 已复核，无代码修复 | 本轮生产改动不增加凭据读取或日志字段，LocalizedKeyCache 的 SecretOctetString 和既有脱敏路径不变；测试使用既有合成凭据，不在报告或断言中输出凭据。 |
| 5，状态字符串 | 有意保留，未声称修复 | 状态写入集中于 DeviceStatusMachine.setStatus，SNMPDevice.publishStatus 只决定日志级别，DeviceDiagnostics 的 Starting 是初始展示值。现有 13 项状态测试覆盖运行恢复、启动失败、License、资源、关闭与旧 generation。没有发现当前标签失配；增加 enum 与展示映射会扩大本轮接口修改。新增状态仍有字符串漂移的维护风险。 |
| 6，runtime shutdown 字符串决定行为 | 已修复 | DeviceState.close(reason, notifyInFlight) 用显式布尔参数控制在途失败通知。仅 runtime.stop 传 true，重配置与 lease close 传 false；reason 只作异常与日志说明。既有关闭/重配置测试继续约束差异。 |
| 7，Snmp4jRequestClient 大类 | 有意保留，未声称修复 | KeyAwareMPv3 与 GenerationSecurityState 已是内部边界，当前修复只需收紧现有校验。拆分 transport/security/engine 文件不会额外证明本次行为，还可能改变锁与内部重试关系。固定依赖版本下已有进程内安全隔离和密钥测试；未来升级仍需复查该耦合，不能将行数当成功能缺陷。 |
| 8，GET/SET 重复校验 | 有意保留，未声称修复 | 两条路径已共用 reportFailure、malformedBinding、unexpectedResponseType 等异常构造；GET 返回批量状态，SET 另要求恰好一个绑定且 OID 一致。未发现本次评审范围内的校验漂移；抽共同阶梯会改动两种不同结果契约，收益不足以扩大范围。 |
| 9，日志参数成组重复 | 有意保留，未声称修复 | DeviceState 的 endpoint 是同一 generation 的固定引用，日志直接从该对象取得 device/host/port/version；没有确认的字段串用缺陷。仅为日志新增 context record 会增加类型和传递步骤，不能改善此次两个缺陷的安全性。 |
| 10，Optional 转换 | 已简化 | snapshot 直接 mapToDouble(intervalMillis).min().orElse(Double.NaN)，删除 min 后再 stream/findFirst 的转换，保留空订阅 NaN 语义。 |
| 11，锁内 executor.execute/shutdown | 有意保留，未声称修复 | 生产执行器为预启动的固定有界线程池和 AbortPolicy，不在 execute 中执行用户回调；同一 monitor 同时保护 admission、pending、ready、draining 和 shutdown。现有实现先标记 shutdown/取消 ticket，再关闭池。贸然移出锁会打开 lane 已置 draining 但 runner 尚未提交时被关闭的窗口。测试的直接执行器用于确定性调度；现有 8 项 CallbackDispatch 测试覆盖拒绝释放、FIFO、shutdown/shutdownNow、旧 generation、跨设备并行，另有 queued lane 容量回收测试。未发现死锁环，本轮保留原同步关系。 |

## Red-green 与最终验证

所有 Gradle 命令的 workdir 均为当前工作树根目录。行为测试使用指定的已批准 seam，未另行申请重复授权。

| 阶段 | 实际结果 |
| --- | --- |
| REPORT 测试夹具初稿 | 前两次单例运行通过，原因是夹具保留用户名，未进入匿名 discovery 的真实风险路径，不能当作 red。调整匿名编码期间出现一次测试编译失败，修正 lambda 捕获后继续。 |
| Spec 1 red | 使用匿名 REPORT 与旧生产检查，单例 1 项失败，失败点为新引擎提示被旧报文覆盖。收紧接收观察器后，再临时恢复旧检查复核，仍为同一断言 1 项失败，随后恢复修复。 |
| Spec 1 green | 整组 Snmp4jKeyCacheTest 12 项通过，包含并发发现、内部 time-window REPORT 重试、旧在途响应、静默引擎替换、1024 LRU 与热缓存派生计数。 |
| Spec 2 red | 单例 1 项失败，Bad_TypeMismatch 已成立，但 sourceTime 为 null，与固定 receivedAt 不符。 |
| Spec 2 green | 同一单例 1 项通过，首次错误发布与缓存重发均走真实 codec 异常路径。 |
| Standards 改动后全量 | 指定 gateway/base 命令通过，9 actionable tasks，3 executed、6 up-to-date。 |
| 最终夹具时序修正后全量 | 下述相同指定命令通过，BUILD SUCCESSFUL in 12s，9 actionable tasks，2 executed、7 up-to-date。 |

最终命令：

```bash
bash /home/sheon/orca/workspaces/snmp-driver/m0-integration/gradlew :gateway:test :base:test -x :web-ui:viteBuild
```

最终实际执行 `:gateway:compileJava`、`:gateway:test`。UP-TO-DATE 的 actionable tasks 为 `:base:compileJava`、`:common:compileJava`、`:web-ui:jar`、`:gateway:processResources`、`:gateway:compileTestJava`、`:base:compileTestJava`、`:base:test`。classes/testClasses 等生命周期任务不列入 actionable 数量；processResources 等无来源任务显示 NO-SOURCE。没有执行 web-ui:viteBuild。

XML 来自根目录下 `gateway/build/test-results/test/TEST-*.xml` 与 `base/build/test-results/test/TEST-*.xml`，按 tests/failures/errors/skipped 属性求和，不按注解数量估算：

| 模块/测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| gateway / DeviceDiagnosticsTest | 1 | 0 | 0 | 0 |
| gateway / DeviceStatusMachineTest | 13 | 0 | 0 | 0 |
| gateway / CallbackDispatchTest | 8 | 0 | 0 | 0 |
| gateway / DeviceCircuitBreakerTest | 1 | 0 | 0 | 0 |
| gateway / HalfOpenRecoveryTest | 25 | 0 | 0 | 0 |
| gateway / SharedPollCoordinatorTest | 16 | 0 | 0 | 0 |
| gateway / Snmp4jKeyCacheTest | 12 | 0 | 0 | 0 |
| gateway / Snmp4jRequestClientTest | 9 | 0 | 0 | 0 |
| gateway / Snmp4jSecurityIsolationTest | 6 | 0 | 0 | 0 |
| gateway / SnmpValueCodecTest | 1 | 0 | 0 | 0 |
| gateway / SubscriptionBridgeTimestampTest | 1 | 0 | 0 | 0 |
| **gateway 合计，本轮实际执行** | **93** | **0** | **0** | **0** |
| base / LicenseAccessSnapshotTest | 2 | 0 | 0 | 0 |
| base / LicenseRequirementTest | 4 | 0 | 0 | 0 |
| base / AbstractRuntimeServiceTest | 4 | 0 | 0 | 0 |
| **base 合计，复用既有结果** | **10** | **0** | **0** | **0** |

base XML 时间为 `2026-10-19T08:12:43`，本轮 base:test 为 UP-TO-DATE，不能表述为本轮执行了 10 项 base 测试。编译仍提示现有 deprecated API，Gradle 仍提示 Java 安装探测路径与将来的 Gradle 9 兼容问题，测试日志仍有多个 SLF4J provider 的提示；这些提示未导致任务失败。

## 交付与剩余边界

只提交本次修复、测试与本报告；既有 `.omp/` 未跟踪内容和本地策略/配置不暂存。未删除文件或目录，未改 tracker、推送、PR 或其他外部协作渠道。最终提交 SHA 由执行后的 worker_done 回报。

本轮未运行 Gateway、UI、Docker、storm、net-snmp、CPU 基准或其他系统检查，也未运行额外完整 build。进程内 UDP 测试仅证明固定 SNMP4J 版本下的回归；动态项现有双通道发布所有权与真实客户端最终通知顺序仍属于后续系统验收。保留的 Standards smell 已在表中逐项列出，不能称为全部消除。M0-S 关于全局尺寸/计数器读取、1200 字节仅为估算、配置迁移及后续 Notification 的边界保持原样，本报告不表示 B1 已可发布。
