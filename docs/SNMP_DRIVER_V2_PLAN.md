# SNMP Driver v2 方案：B1（M0 加固 + Trap/Inform 接收）

- 目标版本：**1.1.0**（当前 `gradle.properties:7` 为 `moduleVersion=1.0.0`）
- 基线：HEAD `1dfd778`，branch `main`
- 输入：`/tmp/snmp-driver-analysis.md`（v1 分析与方案 A/B/C）、`/tmp/snmp-review-codex.md`（Codex 复核）、`/tmp/snmp-review-omp.md`（OMP 复核）
- 编写方式：只读核对源码与 SNMP4J 3.12.2 官方源码包（Maven Central `snmp4j-3.12.2-sources.jar`，解压到临时目录后只做阅读）。**本文档编写期间没有运行 build/test/lint/Gateway 验证，也没有修改代码。**
- 行号规则：本文中所有 `file:line` 都是本次亲自打开当前文件后确认的，没有沿用三份旧报告的行号。无法在本地确认的写作「待验证」。
- 路径缩写：`S/` = `gateway/src/main/java/com/jw/snmpdriver/`；`B/` = `base/src/main/java/com/jw/ignition/`；`J/` = SNMP4J 3.12.2 源码包中的 `org/snmp4j/`（外部源码，不在本仓库内）。其余路径相对 repo root（`/home/sheon/Projects/snmp-driver`，见 Q15）。
- 契约调查：已纳入 `f99b28d` 的 [M0-S 调查记录](research/M0S_CONTRACT_FINDINGS.md)。静态结论与待实测项目分开记录；本次修订未运行验证。
- 修订：已回写 grilling 决策 Q1–Q18 与 M1a grilling 决策 Q19–Q23（§1.1）。术语以 `CONTEXT.md` 为准（Q6）；ADR 以仓库 `docs/adr/` 为权威来源（Q14）。

---

## 1. 背景与决策摘要

v1 分析给出 A/B/C 三套方案，推荐 B 分期交付。两份复核都同意 B 的增量方向，但指出了以下问题：M0 工期低估；遗漏了 HALF_OPEN 熔断卡死（N1）、时间戳契约（N2）、socket 泄漏（N3）等风险；v1 的 file:line 有系统性错位；Trap 事件语义、ACK 时点、License 行为和端口示例都没有写清楚。

本方案只覆盖 **B1**。以下是用户已拍板的决策，按原意写入，不做改写：

| # | 决策 |
|---|---|
| D1 | 采用推荐路线 B 的首期 **B1 = M0 加固 + Trap/Inform 接收**，目标版本 **1.1.0**。 |
| D2 | **Web UI**：保持 `web-ui/src/packages` 只允许 `page/license`，B1 不加任何页面；Trap 状态通过 OPC 节点/Tag 暴露。 |
| D3 | **MIB 解析**：本次完全排除实现，只用 ADR 记录未来方向。首选离线字典（snmptranslate/smidump/pysmi→JSON，模块只负责加载字典）；备选 SNMP4J-SMI-PRO（商业授权）；Mibble 为 GPL，不适用。B1 中 Trap 以原始 OID 展示。 |
| D4 | **162 端口**：可配置，默认 162。绑定失败时只有 Trap 功能进入 Degraded，不影响轮询，LastError 写明原因与处置办法。Linux 首推让 Ignition 监听高端口（如 10162），再用 nftables 把 162 转发到 10162，不推荐 setcap；Windows 停用系统 SNMP Trap 服务；Docker 用 `-p 162:10162/udp`。同一端口的多个设备共享一个 listener；同一 bind 地址/端口的参数有冲突时，在保存时校验报错。 |
| D5 | Event Streams、MIB、Walk/Table/Browse、多 varbind SET/RowStatus、SQL TrapStore 都放在 B1 之后，只列入路线图。 |

### 1.1 grilling 决策（Q1–Q18）

以下决策由 grilling 确定，按原文写入，不做改写；正文各节已同步：

| # | 决策 | 正文位置 / ADR |
|---|---|---|
| Q1 | Notification 只归属已配置 Device，不做 Gateway 级任意来源入口；只发 Trap 的设备建无 Tag 的 Device。 | §4.1、§4.3；`docs/adr/0001-notifications-belong-to-devices.md` |
| Q2 | 接受 Notification Snapshot（计数+最近一条）语义，手册写明；逐条审计在路线图。 | §4.7、§7；`docs/adr/0002-notification-snapshot-semantics.md` |
| Q3 | 冗余 Gateway B1 不支持，手册注明。 | §4.1、§5.2 |
| Q4 | v3 Trap 强制配置 trapSenderEngineId；手册给 snmpget snmpEngineID.0 查询方法。 | §4.6、§4.8；`docs/adr/0003-v3-trap-requires-sender-engine-id.md` |
| Q5 | 无 License：端口照开，丢弃计数，Inform 不 ACK，Status=Unlicensed，质量 Bad_License*，恢复后自动接收。 | §4.9 |
| Q6 | 术语以 CONTEXT.md 为准（Notification/Trap/Inform/Trap Listener/Unmatched Notification/Notification Snapshot）；面向用户的节点/配置名保留 Traps/、trap*。 | §4 开头术语说明 |
| Q7 | 多 Device 匹配时广播。 | §4.3；`docs/adr/0001-notifications-belong-to-devices.md` |
| Q8 | trapCommunity 为空沿用 readCommunity；认证失败计 AuthFailureCount，LastError 提示检查 Trap Community。 | §4.3、§4.8 |
| Q9 | Inform 内存入队后 ACK，不保证持久化。 | §4.5；`docs/adr/0004-inform-ack-after-in-memory-enqueue.md` |
| Q10 | 保留 TRAP.* 动态路径（每 Device 256 个 OID）。 | §4.7 |
| Q11 | 限速默认 100/s、burst 200，可按 Device 调 1–10000。 | §4.8 |
| Q12 | 硬性要求：1.0.0 设备配置升级到 1.1.0 不改即可加载，Trap 默认关闭，轮询不变；列为 M1 退出条件，在 igdev 导入 1.0.0 配置做升级测试。 | §4.8、§5.1、§5.2、§6.3 |
| Q13 | M0 包含 P0 全部 + R2、R7、R8、N2；M0-9 顺延到 B1 之后（移入路线图）；相应重算工期。 | §2.3、§3、§5.1、§7 |
| Q14 | 仓库 docs/adr/ 为 ADR 权威来源。 | §1 末尾 |
| Q15 | 以 Linux 环境为准：repo root /home/sheon/Projects/snmp-driver，命令 ./gradlew。 | §6 |
| Q16 | worker 长期授权在 repo root 运行 ./gradlew :gateway:test、:base:test、build；igdev Gateway 系统测试、Docker、风暴测试、web-ui 命令需逐次询问用户。 | §5.1、§6 |
| Q17 | 每里程碑（M0-S、M0、M1a–d）一个 GitHub Issue、一个分支、一个 PR，不直接 commit main；开 Issue/PR 前询问用户；Opus 5.5 high 实现，codex gpt-6.1-sol high 交叉 review，coordinator 统筹验收。 | §5.4 |
| Q19 | 跨设备冲突以运行期 `registry.acquire` 复查为准；保存期跨设备拒绝不作为 M1a 门槛，另开 spike 验证，不可行则只保留运行期降级。 | §4.8；`docs/adr/0007-cross-device-validation-runtime-first.md` |
| Q20 | M1a 做真实 bind，不做接收：可注入 `ListenerBinder`、状态机、引用计数、`Traps/Status`、`LastError`、`ListenerEndpoint`；收到的包直接丢弃，responder、队列、`TrapEngineIdentity` 留给 M1b/M1c。`Traps/` 三个节点不论 `trapEnabled` 都创建，关闭时 `Status=Disabled`。 | §4.2、§4.7 |
| Q21 | bind 失败固定 30 s 重试，不退避，仅在仍有引用时重试；使用 ServiceBundle 现有 scheduler。 | §4.2 |
| Q22 | 1.0.0 升级兼容：覆写 `decode(JsonElement)`，仅为缺失的 `trap*` 字段补默认值，显式非法值保留交给 `validate`；不使用 `customizeGson`。 | §4.8 |
| Q23 | listener 以端口为键，第一个 acquirer 的 bindAddress 定义该 listener，bindAddress 不一致的后来者被拒绝并 Degraded；`trapPort` 默认保持 162；表单 Label 与 Linux 端口提示列为 M1a 验收项。 | §4.2、§4.8、§4.10 |
| Q18 | agent 只更新 Markdown（Feature List、README），另写 docs/USER_MANUAL_1.1.0_CHANGES.md 列出 .docx 手册需改内容，.docx 由人工更新。 | §4.12、§5.1（M1d 交付物） |

### 1.2 ADR

仓库 `docs/adr/` 是 ADR 的权威来源（Q14）：

- `docs/adr/0001-notifications-belong-to-devices.md`：Notification 只归属已配置的 Device（Q1、Q7）
- `docs/adr/0002-notification-snapshot-semantics.md`：B1 只暴露 Notification Snapshot（Q2）
- `docs/adr/0003-v3-trap-requires-sender-engine-id.md`：v3 Trap 强制配置发送方 engineID（Q4）
- `docs/adr/0004-inform-ack-after-in-memory-enqueue.md`：Inform 在进入内存队列后即 ACK（Q9）
- `docs/adr/0005-trap-port-policy.md`：Trap 端口默认 162，绑定失败只降级 Trap 功能（D4）
- `docs/adr/0006-defer-mib-parsing.md`：推迟 MIB 解析，未来首选离线字典（D3）
- `docs/adr/0007-cross-device-validation-runtime-first.md`：跨设备冲突以运行期复查为准（Q19）

知识库中的 ADR 草稿（`draft` / `agent_access: deny`，等待人工审批）只作为副本，各自注明对应的仓库 ADR：

- `/home/sheon/Knowledge/Brain/40-Decisions/snmp-driver/ADR-mib-parsing-strategy.md` → `docs/adr/0006-defer-mib-parsing.md`
- `/home/sheon/Knowledge/Brain/40-Decisions/snmp-driver/ADR-trap-port-policy.md` → `docs/adr/0005-trap-port-policy.md`
- `/home/sheon/Knowledge/Brain/40-Decisions/snmp-driver/ADR-webui-scope-b1.md` → 无对应仓库 ADR，D2 沿用 `AGENTS.md` 现有规则（`web-ui/src/packages` 只允许 `page/license`）

---

## 2. 现状与已核实风险清单

### 2.1 现状要点（已重新核对）

| 项 | 现状 | 证据 |
|---|---|---|
| 模块作用域/依赖/Hook | 全部 G 作用域；只依赖 `com.inductiveautomation.opcua`；Hook 为 `com.jw.snmpdriver.gateway.GatewayHook` | `build.gradle:52-57`、`build.gradle:59-61`、`build.gradle:63-66` |
| 最低 Ignition 版本 | `ignitionVersion=8.3.0`，同时作为 `requiredIgnitionVersion`；igdev 开发 Gateway 是 8.3.10。这是正常的最低兼容策略，不算缺陷 | `gradle.properties:2`、`build.gradle:40`、`igdev.toml:13` |
| SNMP4J | 3.12.2，`implementation` 与 `modlImplementation` 双声明 | `gateway/build.gradle:21`、`gateway/build.gradle:23` |
| gateway 测试 | `gateway/src/test` 不存在。但 JUnit 5.10.3 和测试运行时依赖**已经声明**，可以直接新增测试源集 | `gateway/build.gradle:26-31` |
| 开发包签名 | `skipModlSigning.set(true)` | `build.gradle:50` |
| 共享运行时 | `ServiceBundle.ensureStarted()` 依次创建 DnsResolver、Licensed→Snmp4j 客户端、2 线程调度器和协调器 | `S/gateway/service/ServiceBundle.java:91-126` |
| 传输 | 一个 `Snmp` 实例挂 IPv4/IPv6 两个 `DefaultUdpTransportMapping` | `S/snmp/runtime/Snmp4jRequestClient.java:63-71` |
| 请求接口 | 只有 `getBatch` / `set` | `S/snmp/runtime/SnmpRequestClient.java:10-19` |
| 诊断节点 | 8 个：Status、ActiveSubscriptionCount、ActiveOidCount、MinimumEffectivePollInterval、CircuitBreakerState、LastSuccessTime、LastFailureTime、LastError | `S/gateway/device/DeviceDiagnostics.java:68-81` |
| Item Path 语法 | `<OID>`、`<Type>/<OID>`、`RO.<Type>/<OID>`、`RW.<Type>/<OID>`；只允许一个 `/` | `S/snmp/address/ItemPathParser.java:6-70`（单 `/` 约束在 `:25`） |
| 数字 OID | 正则 `[0-9]+(?:\.[0-9]+)+` 加 ASN.1 边界检查 | `S/snmp/address/NumericOid.java:19-37` |
| Web UI | 只有 License 页 | `S/gateway/GatewayHook.java:115-136`；`web-ui/src/packages/page/` 下只有 `license` 与 `index.ts` |
| 文档漂移 | README 仍写 DeviceExtensionPoint「将在后续加入」；Feature List 版本写 `0.1.0-beta7`；Trap/Inform/MIB 在「当前不包含」中 | `README.md:5`、`docs/SNMP_DRIVER_FEATURE_LIST.md:8`、`docs/SNMP_DRIVER_FEATURE_LIST.md:37` |

### 2.2 能力矩阵的修正（只列与 v1 不一致之处）

- v3 Auth/Priv 枚举在 `S/gateway/device/SNMPDeviceConfig.java:55-56`，不是 v1 写的 `:44/:45`。映射位置：`S/snmp/runtime/Snmp4jRequestClient.java:392-399` 与 `:412-417`。
- 熔断降级只在「circuitFailure 且旧值非空」时返回 `Uncertain_LastUsableValue`，其余情况返回 Bad：`S/gateway/device/DeviceDiagnostics.java:121-135`。
- License 门禁位置：`S/snmp/runtime/LicensedSnmpRequestClient.java:31-56` 与 `:68-97`（v1 写的 `:289-318` 不存在）。
- BITS/TruthValue 可以按 OctetString/ByteString、Integer32 读取原始值，缺少的只是 MIB 语义转换（`S/snmp/address/SnmpWireType.java:8-22`）。
- OctetString 严格 UTF-8：0x00 本身是合法 UTF-8，带 NUL 结尾**不会**导致 `Bad_DecodingError`（`S/snmp/runtime/SnmpValueCodec.java:170-184`）。

### 2.3 风险清单最终结论

结论取值：**采纳**（成立，按原意处理）／**修正**（成立，但机制、证据或严重度需要更正）／**驳回**（不成立或不构成问题）。「B1 处理」一列说明它在本期 M0/M1 中的去向，其中的 M0-n 编号与 §3 小节编号一致；原 M0-9 各项按 Q13 顺延到 B1 之后。

| # | 结论 | 核对后的事实与证据 | B1 处理 |
|---|---|---|---|
| R1 IPv6 绑定失败拖垮驱动 | **采纳（严重度上调）** | `Snmp4jRequestClient.java:66-69` 无条件创建 `[::]:0` mapping。SNMP4J 在**构造时就 bind**（`J/transport/DefaultUdpTransportMapping.java:83-91`；无参构造在 `:64-66` 立即 `new DatagramSocket`），所以 OMP 所说的「lazy bind」**驳回**。异常经 `ServiceBundle.java:120-125` 变成 `IllegalStateException`；模块启动时 `ServiceBundle.java:53-56` → `GatewayHook.java:49-58` 会让**整个模块 startup 失败**，不只是单个设备失败。 | M0-3 |
| R2 SNMP4J 单线程分发 | **修正** | `Snmp4jRequestClient.java:70` 使用 `new Snmp(transport)`，它会创建默认的 `MessageDispatcherImpl`（`J/Snmp.java:229-230`、`:273-279`）。响应回调在 transport 监听线程中同步执行：`SharedPollCoordinator.java:441` 的 `thenCompose` → `:447-539`（持 DeviceState 锁）→ `finishSuccess :610-623` → `publishValue :985-990` → `SubscriptionBridge.java:224-246`（codec + `item.setValue`）。风险成立，但「必然大面积丢包」需要压测才能下结论。`MultiThreadedMessageDispatcher` 存在（`J/util/MultiThreadedMessageDispatcher.java:71`），但它用的 `ThreadPool.execute` 在线程全忙时会**阻塞**（`J/util/ThreadPool.java:81-125`），只是把背压转移到监听线程，并且没有丢弃计数，所以**不选它作为主方案**。 | M0-5 |
| R3 Errored 粘滞 | **采纳 + 修正修复方案** | `SNMPDevice.java:483`、`:494`、`:503` 遇到 Errored 直接 return；`:515-516` 中 unexpected 会转为 Errored。分类依据：`SnmpFailureClassifier.java:58-66`（SnmpConfigurationException）、`:103-111`（SecurityException）、`:164-165`（IllegalArgumentException）、`:167`（兜底的 runtime 异常）。**对 OMP「classify 缺 phase 参数」的修正**：启动期失败在 `SNMPDevice.java:336`、`:346` 直接调用 `setStatus("Errored")`，从不经过 `handleFailure`；`handleFailure` 的调用方全部在运行期（`:193`、`:226`、`:253`，以及 bridge/writer 回调 `:274`、`:296`）。因此 phase 可以在 SNMPDevice 层区分，不必改 `classify` 的签名。**M0-S 修正**：当前 `toDottedString` 使用固定数字格式化，解码输出不能据此推导出 NumericOid 越界 IAE；撤回远端越界 OID 触发链的说法。一般运行期 Errored 粘滞仍成立，保留恢复修复。证据见调查记录 §5.5。 | M0-4 |
| R4 <1000 ms 拒绝而不修订 | **修正** | 行为成立：`SNMPDevice.java:97-110` 只修订 null/NaN/负数；`DynamicAttributeReader.java:130-133` 注释说明这是刻意选择；`SubscriptionBridge.java:409-428`、`SharedPollCoordinator.java:205-211` 会拒绝。这属于兼容性行为变更，「违反规范」的说法证据不足。 | B1 不改，手册写明；路线图待决策 |
| R5 写入同步阻塞 | **采纳** | `DynamicAttributeWriter.java:33`（60 s 上限）、`:147-153`、`:175`；`SNMPDevice.java:213-233` 逐项串行。具体占用哪个线程池待验证。 | 路线图 |
| R6 SubscriptionModel 重复采样 | **修正，静态已确认** | Milo 定期 read 后 setValue；当前动态 read 只取 SubscriptionBridge 缓存，不重复发送 GET，也不能推断 Bad 必然变 Good。双通道发布存在时序风险，实际通知与时间戳仍需 Gateway 实测。见调查记录 §1。 | M0-8 核对时间戳；发布所有权调整留后续专项，不在 M0 顺手重构 |
| R7 全局单例污染 | **修正** | `Snmp4jRequestClient.java:72`、`:84-87`。另外 `new Snmp(transport)` 内部的 `initMessageDispatcher` 用的是无参 `new MPv3()`，它本身就走全局 `SecurityModels`/`SecurityProtocols`（`J/Snmp.java:247-253`）。`MPv3(USM)` 只隔离 SecurityModels，仍然使用全局 SecurityProtocols（`J/mp/MPv3.java:139-145`）；主安全模型隔离需用 5 参构造（`J/mp/MPv3.java:169-172`）加 `new SecurityProtocols(SecurityProtocolSet)`（`J/security/SecurityProtocols.java:112-115`）。五参构造仍有全局尺寸估算、计数器等读取，不能称为整个库完全隔离，见调查记录 §5.1。「热重载必然互相覆盖」说得过满，**驳回**这一部分。但新增 Trap receiver 之前**必须**完成隔离。 | M0-6 |
| R8 DirectUserTarget 每请求重派生密钥 | **采纳（证据升级为已确认）** | 每次请求都 new 一个带口令的 target（`Snmp4jRequestClient.java:346-365`）。发送时 `MessageDispatcherImpl` 从 MPv3 缓存取出 engineID 写入 target（`J/MessageDispatcherImpl.java:629-635`、`:684-697`），触发 `localize` → `passwordToKey`（`J/DirectUserTarget.java:325-370`）。CPU 影响规模待基准测试。 | M0-7 |
| R9 tooBig 不学习 | **修正** | `SharedPollCoordinator.java:457-466` 只递归拆当前批次，`selectBatch :541-568` 没有学习上限。「往返翻倍」不精确：拆两片时是 1 次失败加 2 次成功，递归时次数更多。 | 路线图（原 M0-9，Q13 顺延到 B1 之后） |
| R10 License POST 只要求 READ | **修正** | 权限常量在 `LicenseManagementRouteRegistrar.java:13-16`，POST 挂载在 `:50-63`；另外 GET `/license/status` 会触发 `license.refresh()`（`:66-72`）。实际能否被只读用户访问取决于 Gateway 的 Web UI 访问配置。 | 路线图（改为 WRITE 并保留 CSRF，需负责人确认） |
| R11 OctetString 严格 UTF-8 | **修正** | `SnmpValueCodec.java:138-141`、`:170-184` 只拒绝非法 UTF-8；NUL 的说法**驳回**。可以用 ByteString 作为无损出口。 | 手册说明 |
| R12 启动失败不自动重试 | **采纳（低）** | `SNMPDevice.java:309-348`、`:587-614` 只做清理并设 Errored。重启设备或模块也能恢复，「只能保存配置」说得过窄。 | 路线图 |
| R13 版本/文档漂移 | **修正** | README 与 Feature List 过时成立（`README.md:5`、`docs/SNMP_DRIVER_FEATURE_LIST.md:8`）。编译 8.3.0、运行 8.3.10 **不算漂移**（`build.gradle:40`）。 | M1d 发布时统一（Q18） |
| R14 gateway 零测试 | **采纳** | `gateway/src/test` 不存在；base 有 3 个测试类（`base/src/test/java/com/jw/ignition/common/{license,service}/`）。测试依赖已就绪（`gateway/build.gradle:26-31`）。 | M0-1 |
| R15 单 v3 身份/context | **采纳（需求限制，不算缺陷）** | `SNMPDeviceConfig.java:25-38`；v3 分支不区分读写（`Snmp4jRequestClient.java:352-365`）。B1 的 Trap 也沿用这一个 v3 身份。 | 维持 |
| N1 HALF_OPEN 永久卡死 | **采纳（高）** | `DeviceCircuitBreaker.java:19-25` 把状态置为 HALF_OPEN；只有 `onValidResponse :27-31` 和 `onTransportFailure :33-41` 能离开这个状态。若探测结果既非 circuitFailure 也非 legalResponse（License 失败见 `LicensedSnmpRequestClient.java:36-41`、`:68-97`，分类见 `SnmpFailureClassifier.java:38-47`），SET 路径 `SharedPollCoordinator.java:383-391` 和 GET 路径 `:408-421` 都不会改变熔断状态。`scheduleNext` 遇到 HALF_OPEN 直接 return（`:849-851`）；之后 `wake` 的 `allowNormal`/`tryHalfOpen` 都会失败并 break（`:299-309`）。补充两种同类情况：选中探测但没有可发送的批次时 break（`:329-334`），状态仍是 HALF_OPEN；此时新增订阅触发的 `schedule(0)`（`:219`）同样会卡住。 | M0-1/M0-2 首要 |
| N2 成功值没有 source timestamp | **采纳** | `RawBinding.java:8` 带有 `receivedAt`，赋值在 `Snmp4jRequestClient.java:247`、`:257`、`:334`；但 `SnmpValueCodec.java:40-42` 只构造 `new DataValue(new Variant(...))`。`publishCachedDue`（`SharedPollCoordinator.java:999-1007`）会周期重发旧 binding；`DeviceDiagnostics.java:130` 用的 `lastKnown.getSourceTime()` 因此没有意义。`SNMPDevice.java:168-201` 的 read 忽略 maxAge。 | M0-8 |
| N3 构造失败时 socket 泄漏 | **采纳** | `Snmp4jRequestClient.java:65` 已经 bind IPv4；`:66-69` 失败后对象没有构造完成。`ServiceBundle.java:102-105` 的赋值不会发生，`closeRuntimeParts :181-187` 关不到 IPv4 socket。 | M0-3（与 R1 一起修） |
| N4 DNS 超时无法取消底层解析 | **采纳（中）** | `DnsResolver.java:29-41`（2 线程、128 队列），`:51-54`（`orTimeout` 不取消底层任务），`:70-80`（AUTO 取第一个地址、无回退，见 `:72-75`）。 | 路线图；M0-3 只做「IPv6 不可用时 AUTO 优先 IPv4」 |
| OMP-E DNS 5 min 单 IP 缓存 | **采纳（低）** | `DnsResolver.java:25`（TTL），`:44`（缓存键）。Trap 按源地址路由时同样受影响（见 §4.3）。 | M1 路由刷新时考虑；其余进路线图 |
| N5 SET 超时后远端结果未知 | **采纳** | `DynamicAttributeWriter.java:179-194` 已注明 outcome unknown；SET 同样使用 retries（`Snmp4jRequestClient.java:377-378`）。 | 手册说明；RowStatus 设计的前置约束 |
| N6 动态读拒绝缓存无界 | **采纳（低）** | `DynamicAttributeReader.java:39`、`:113`、`:165-183`；对比协调器的 512 上限 `SharedPollCoordinator.java:699-704`。 | 路线图（原 M0-9，Q13） |
| N7 凭据轮换/TCP/TLS/contextEngineID 无支持 | **采纳（范围说明）** | Secret 只在启动时解析（`SNMPDevice.java:471-479`）；只有 UDP（`Snmp4jRequestClient.java:63-71`）；只设置 contextName（`:337-344`）。 | 手册「不支持」列表 |
| OMP-A 报告证据错位 | **采纳** | 本文已全部重新锚定。 | — |
| OMP-B R3 需要 phase 区分 | **修正** | 需要区分 phase，但在 SNMPDevice 层就能做到（见 R3 行）。 | M0-4 |
| OMP-C SET 无 tooBig 处理 | **修正** | 单 varbind SET 收到 tooBig 无法再拆。现状已映射为 `Bad_EncodingLimitsExceeded`（`SnmpFailureClassifier.java:235`，经 `:113-122` 判为 legalResponse，熔断闭合），由 `Snmp4jRequestClient.java:301-308` 抛出。缺的是可操作的诊断文案和测试；多 varbind SET 时才需要真正的拆分策略。 | 路线图（原 M0-9，Q13） |
| OMP-D 默认 1200 B 必然频繁 tooBig | **修正 / 部分驳回** | 默认值与估算公式成立，但 1200 是批次估算预算，不是编码后硬上限，单个超预算条目仍可发出。v3 开销取决于名字、engineID、算法与 BER 长度；160 B 必然不足、默认必然频繁 tooBig 均无充分证据。保留默认值，后续测完整请求/响应长度和 tooBig 次数，见调查记录 §9。 | 路线图（原 M0-9，Q13） |
| OMP-F v1 SET OID 回显假设 | **驳回** | RFC 1157 规定 v1 GetResponse 成功时回送请求的 varbind 列表，errorIndex 在 v1/v2c 都是 1-based；`Snmp4jRequestClient.java:323-333` 的检查对 v1 成立。 | — |
| OMP-G `snmp.close()` 已存在 | **采纳（附条件）** | `Snmp4jRequestClient.java:651-659`、`ServiceBundle.java:181-187`。只对构造完成的客户端有效，构造失败的情况见 N3。 | — |
| Codex：162 示例自相矛盾 | **采纳** | v1 的 `10162:162/udp` 与 `snmptrap <gw>:162` 不一致。按 D4 统一为 `-p 162:10162/udp`，模块端口配 10162，发送方发往宿主机 162。 | §4.10 |
| Codex：Inform 自动 ACK 时点 | **采纳（已用 3.12.2 源码确认）** | 走 `addNotificationListener` 时，`NotificationDispatcher.processPdu` 会**先**回复 Inform，**再**调用 listener（`J/Snmp.java:2183-2201`，ACK 构造见 `:2213-2227`）。B1 要求「入队成功后才 ACK」，所以不能用这条路径（见 §4.5）。 | M1 |
| Codex：USM 支持未本地化用户的现场本地化 | **采纳（已确认）** | `J/security/USM.java:349-386`、`:390-415`；默认 `LocalizationGrant.any`（`J/security/UsmUser.java:84`）。开放任意 engineID 有 CPU/内存风险。 | M1 v3 设计 |
| v1 Event Streams 需要 SDK ≥ 8.3.6 | **驳回（Codex 的反证）** | 与 B1 无关，留到路线图再核对具体签名。 | 路线图 |

---

## 3. M0 加固范围

总原则：先建测试基线，再修改控制流；每项修改都带回归测试；不加占位实现；不扩大公共接口。各项优先级：P0 = 发布阻断，P1 = B1 必做，P2 = 顺延到 B1 之后。

**M0 范围（Q13）**：P0 全部（M0 契约调查、M0-1…M0-4）+ R2（M0-5）、R7（M0-6）、R8（M0-7）、N2（M0-8）。M0-9 顺延到 B1 之后，移入路线图（§7）；下文保留其设计，仅供后续版本参考。

### M0-1 测试基线（P0）

- **设计**：新建 `gateway/src/test/java/com/jw/snmpdriver/...`（依赖已声明在 `gateway/build.gradle:26-31`）。
  - 引入 `FakeSnmpRequestClient`，实现 `SnmpRequestClient` 接口，可以编程控制每次 GET/SET 的结果、延迟，以及是否同步失败。
  - `SharedPollCoordinator` 增加 package-private 构造参数 `LongSupplier nanoClock`，默认仍为 `System::nanoTime`，用来测试退避，避免真实等待。`DeviceCircuitBreaker` 已经接收 `nowNanos` 参数，可以直接测试。
- **改动文件**：`gateway/src/test/**`（新增）；`S/snmp/runtime/SharedPollCoordinator.java`（只注入时钟）。
- **验收**：以下纯逻辑类有单测：`ItemPathParser`、`NumericOid`、`SnmpValueCodec`、`SnmpFailureClassifier`、`DeviceCircuitBreaker`、`SharedPollCoordinator`（批次、tooBig、v1 noSuchName、SET 队列、熔断）。
- **单测**：`ItemPathParserTest`、`NumericOidTest`、`SnmpValueCodecTest`（含 NUL、非法 UTF-8、各 wireType）、`SnmpFailureClassifierTest`（每类异常对应的 category/status/三个标志位）、`DeviceCircuitBreakerTest`、`SharedPollCoordinatorTest`。

### M0-2 N1：HALF_OPEN 必须有出口（P0）

- **设计**：
  1. `DeviceCircuitBreaker` 新增 `abortProbe(nowNanos)`：HALF_OPEN → OPEN，`nextProbeNanos = now + BACKOFF_MS[0]`（加抖动），**不推进** `backoffIndex`，因为探测没有真正到达设备。
  2. `SharedPollCoordinator` 中，探测（`probe=true` 发出的 GET/SET）完成时，凡是既非 circuitFailure、也非 legalResponse 的结果（license、configuration、resource、shutdown、runtime 等）都调用 `abortProbe`。为此在 `wake` 中给本次 dispatch 的批次或 SET 标记 `probe` 并传入完成回调。
  3. 选中探测但 `selectProbeBatch()` 为空时（`:329-334`），同样调用 `abortProbe`。
  4. 防御：`scheduleNext` 遇到 HALF_OPEN 且 `inFlight == 0` 时视同卡死，记录 warn 后调用 `abortProbe` 并重新调度。
  5. License 暂停与网络熔断分开：License 失败不推进退避，也不算网络探测；`abortProbe` 之后最多 2 s 内重试一次，与 License monitor 的 5 s 周期配合（`B/common/license/ModuleLicenseRuntime.java:56-66`）。
- **改动文件**：`S/snmp/runtime/DeviceCircuitBreaker.java`、`S/snmp/runtime/SharedPollCoordinator.java`。
- **验收**：用 fake client 编排「2 次传输失败 → OPEN → 到探测时间 → HALF_OPEN 探测遇到 License 失败 → License 恢复」，断言之后**真实发出了 GET 请求**，值也恢复推送；只看 Status 字符串不算通过。
- **单测**：`DeviceCircuitBreakerTest.abortProbeReturnsToOpenWithoutEscalation`；`SharedPollCoordinatorTest` 中的 `halfOpenLicenseFailureRecovers`、`halfOpenConfigurationFailureRecovers`、`halfOpenWithoutDispatchableWorkReturnsToOpen`、`subscribeDuringHalfOpenEventuallyPolls`、`setProbeLicenseFailureRecovers`。

### M0-3 R1 + N3：IPv6 可选，构造失败不泄漏（P0）

- **设计**：
  1. `Snmp4jRequestClient` 构造改为「局部变量 + 失败时关闭」：先创建 IPv4 mapping；IPv6 mapping 放在独立的 try 中，失败时记录 warn（附原因），`ipv6Transport = null`，并保存 `ipv6UnavailableReason`。只有 IPv4 失败才向外抛异常，抛出前关闭所有已创建的 mapping。
  2. `createTarget` 遇到 IPv6 目标而 `ipv6Transport == null` 时，返回失败的 future，异常类型为新增的 `SnmpTransportUnavailableException`，消息为 `IPv6 transport unavailable on this Gateway: <reason>`。分类为 category `transport-unavailable`、`Bad_NoCommunication`、circuitFailure=false、legalResponse=false、unexpected=false；`SNMPDevice.handleFailure` 把它映射为 Degraded。
  3. `DnsResolver` 在 AUTO 模式且 IPv6 不可用时优先选择 IPv4 地址（只改 `:72-75` 的选择顺序，不重写缓存）。
  4. `ServiceBundle.ensureStarted` 先用局部变量构造完整对象，成功后再发布到字段；catch 中关闭已创建的局部对象。
- **改动文件**：`S/snmp/runtime/Snmp4jRequestClient.java`、`S/snmp/runtime/DnsResolver.java`、`S/gateway/service/ServiceBundle.java`、`S/snmp/runtime/SnmpFailureClassifier.java`、新增 `S/snmp/runtime/SnmpTransportUnavailableException.java`、`S/gateway/device/SNMPDevice.java`。
- **验收**：在 IPv6 被禁用的主机或容器上，模块能正常启动，IPv4 设备正常轮询；IPv6 设备显示 Degraded，LastError 写明原因。IPv4 构造失败时没有残留的 DatagramSocket。
- **单测**：把 mapping 创建抽成 package-private 工厂（`TransportFactory`），在测试中注入「IPv6 抛 SocketException」，断言 IPv4 mapping 已关闭（IPv4 也失败时）或客户端可用（只有 IPv6 失败时）；`DnsResolver` 的地址优选单测；`SnmpFailureClassifierTest` 覆盖新异常。

### M0-4 R3：启动期粘滞、运行期可恢复（P0）

- **设计**：
  1. 把 SNMPDevice 的状态转换抽成纯 Java 的 `DeviceStatusMachine`（同包，package-private），用于单测。SNMPDevice 委托给它，`setStatus` 的日志行为保持不变。
  2. 新增 `startupFailed` 标志，只在 `onStartup` 的两个 catch（`SNMPDevice.java:309-348`）中设置。只有它让 Errored 粘滞，直到设备重建。
  3. 运行期 unexpected 失败仍显示 `Errored`，但**可以恢复**：`markRunning`/`handleActivity`/`handleFailure` 只在 `startupFailed` 为真时提前 return；运行期 Errored 之后收到合法响应会回到 Running/Idle，License 失败转为 Unlicensed，circuit 失败转为 Degraded。
  4. 保留内部不变量错误分类。M0-S 未支持当前 UDP 解码链产生越界数字 OID 的假设，不把手造 OID 对象当远端复现，也不将内部 parse 失败无条件归为合法协议响应。`getBatch` 的空批次 IAE 属于内部不变量违反，保持 unexpected，但运行期状态可以恢复。
- **改动文件**：`S/gateway/device/SNMPDevice.java`、新增 `S/gateway/device/DeviceStatusMachine.java`、`S/snmp/runtime/Snmp4jRequestClient.java`。
- **验收**：运行期注入 IAE/SecurityException 后设备显示 Errored；随后注入合法响应，设备回到 Running。启动期 Secret/配置错误保持 Errored，不会被后续回调改写。
- **单测**：`DeviceStatusMachineTest`，覆盖全部状态转换表：startup 粘滞；运行期 Errored → Running、→ Unlicensed、→ Degraded；shuttingDown 时忽略回调。无需为未证实的远端越界 OID 路径新增转换工具。

### M0-5 R2：有界回调执行器，保持每设备顺序（P1）

- **设计**：
  1. `ServiceBundle` 创建共享的 `ThreadPoolExecutor callbackPool`：固定 4 线程、`ArrayBlockingQueue(4096)`、`AbortPolicy`，线程名 `snmp-callback-N`。
  2. 每个 `DeviceState` 持有一个 `SerialExecutor`（包装 callbackPool，FIFO，同一时间最多一个任务），保证同一设备的回调顺序。
  3. `executeBatch` 中的 `request.thenCompose(...)`（`:441`）改为 `thenComposeAsync(..., deviceSerial)`；`dispatchBatch`/`dispatchSet` 的 `whenComplete` 改为 `whenCompleteAsync(..., deviceSerial)`。SNMP4J 监听线程上只保留 `completeGet/completeSet` 的轻量解析和 future 完成。
  4. 投递被拒（RejectedExecutionException）时，该批次按 `resource` 失败处理（现有分类见 `SnmpFailureClassifier.java:161-163`），设备显示 Degraded，并增加 `CallbackRejectedCount`（只记入日志与 LastError，不新增诊断节点）。不使用无界队列。
  5. 关闭顺序：协调器 stop → callbackPool `shutdown`，等待最多 2 s → `shutdownNow`。
- **改动文件**：`S/snmp/runtime/SharedPollCoordinator.java`、`S/gateway/service/ServiceBundle.java`、新增 `S/snmp/runtime/SerialExecutor.java`。
- **验收**：fake client 在「监听线程」上完成 future 时，`processBatchResult` 不在该线程上执行；同一设备的回调严格按完成顺序执行；队列满时有明确的失败与日志，且不阻塞完成线程。
- **单测**：`SerialExecutorTest`（FIFO、异常隔离、拒绝传播）；`SharedPollCoordinatorTest.callbacksRunOffCompletingThread`、`callbackRejectionMarksResourceFailure`。

### M0-6 R7：SNMP4J 安全模型隔离（P1，Trap 的前置条件）

- **设计**：在 `Snmp4jRequestClient` 中显式组装：`SecurityProtocols protocols = new SecurityProtocols(SecurityProtocolSet.none)`，再逐个添加现有 6 种 auth 和 4 种 priv 算法（`:73-82`）；`USM usm = new USM(protocols, localEngineId, 0)`；`MPv3 mpv3 = new MPv3(localEngineId, null, protocols, SecurityModels.getCollection(new SecurityModel[]{usm}), CounterSupport.getInstance())`；`MessageDispatcherImpl` 中加入 `MPv1`/`MPv2c`/`mpv3`；最后 `new Snmp(dispatcher, ipv4Transport)`（`J/Snmp.java:312-322`，该构造不会自动添加 MP）。不再调用 `SecurityProtocols.getInstance()` 与 `SecurityModels.getInstance().addSecurityModel`。`authenticationProtocol()`/`privacyProtocol()`（`:388-424`）改为从实例 `protocols` 中查找。
- **M0-S 边界**：`DirectUserTarget.localize` 的静态派生使用传入协议对象；`UsmSecurityParameters.getBERMaxLength` 仍读全局 SecurityProtocols，MPv1/MPv2c 和计数器也有全局路径。验收限定为不注册本模块 USM、不覆盖其他实例，不能据注册表检查宣称全库隔离。残余尺寸估算需 v3 集成测试，详见调查记录 §5.1。
- **改动文件**：`S/snmp/runtime/Snmp4jRequestClient.java`。
- **验收**：构造两个客户端实例后，全局 `SecurityModels.getInstance()` 中没有本模块的 USM；两个实例的 USM 互不覆盖；v3 GET 仍然可用（进程内集成测试）。
- **单测**：`Snmp4jRequestClientIsolationTest`（不发网络包，只检查注册表）；v3 互通放在集成测试（§6.2）。

### M0-7 R8：v3 本地化密钥缓存（P1）

- **设计**：
  1. 在 `Snmp4jRequestClient` 中维护 `LocalizedKeyCache`：键为 (`SnmpEndpoint` 对象身份，即设备 generation；authoritative engineID；auth/priv 协议 OID)，值为本地化后的 auth/priv key（`SecretOctetString`），容量上限 1024，采用 LRU。
  2. 发送前，如果 MPv3 的 engine 缓存中已有该地址的 engineID，就用 7 参构造 `DirectUserTarget(address, securityName, engineID, authProto, authKey, privProto, privKey)`（`J/DirectUserTarget.java:98-110`）直接传入已本地化的 key；否则沿用现有的口令型构造（第一次 discovery）。
  3. 失效条件：设备 generation 变化（新 endpoint 对象）；收到 `SnmpSecurityReportException`（unknownEngineID、wrongDigest、decryptionError、notInTimeWindow）时，清除该 endpoint 的条目和 MPv3 中该地址的 engineID 缓存。SNMP4J 会内部处理部分 REPORT 并重发，不能只依赖业务异常失效；必须在实际 authoritative engineID 变化时重新选键，并防止旧 generation/在途响应污染新缓存。不同口令、不同用户之间不能共用缓存条目。
  4. 每次请求仍然新建 target 对象，避免在并发请求间共享可变 target（maxInflight 最大为 8）。
- **改动文件**：`S/snmp/runtime/Snmp4jRequestClient.java`，必要时新增 `S/snmp/runtime/LocalizedKeyCache.java`。
- **验收**：同一 v3 设备连续轮询时，`passwordToKey` 只在 discovery 或缓存失效后执行；设备换 engineID 后能有界恢复，不能预设一次 REPORT 足够；覆盖同址并发 discovery、内部 REPORT 重试与在途请求，不以 map 单次操作同步证明完整流程原子。
- **单测**：`LocalizedKeyCacheTest`（键隔离、LRU、失效）；集成测试中用计数型 `AuthenticationProtocol` 包装统计派生次数。基准测试（CPU/请求）作为待验证项记录，不作为发布门槛。

### M0-8 N2：时间戳契约（P1）

- **契约**：
  1. 轮询值的 `sourceTime` = `RawBinding.receivedAt`（Gateway 收到响应的时刻）；`serverTime` = 写入 DataItem 的时刻。
  2. `publishCachedDue` 重发的缓存值**保留原 sourceTime**，只更新 serverTime。
  3. 熔断期间的 `Uncertain_LastUsableValue` 保留最后一次成功的 sourceTime（`DeviceDiagnostics.java:121-135` 已有此逻辑，修复后才有意义）。
  4. 未订阅节点读 Value 仍返回 `Bad_WaitingForInitialData`（`DynamicAttributeReader.java:134-135`），B1 不实现按需 GET；`maxAge` 被忽略这一点写进手册。
  5. 动态项当前由 SubscriptionBridge 和 Milo SubscriptionModel 双通道发布。M0 保留现有发布结构，检查缓存 sourceTime 不前移、不凭 setValue 次数推算客户端通知；采样竞态与最终通知需后续 Gateway 实测，见调查记录 §1。
  6. Trap：`sourceTime` = Gateway 接收时刻；发送方 `sysUpTime.0` 以 TimeTicks 原值单独暴露，**不换算为墙钟**。
- **设计**：`SnmpValueCodec.toDataValue` 改为 `new DataValue(variant, StatusCode.GOOD, new DateTime(binding.receivedAt()), DateTime.now())`。
- **改动文件**：`S/snmp/runtime/SnmpValueCodec.java`；核对 `S/gateway/device/SubscriptionBridge.java:232-240` 不覆盖 sourceTime。
- **验收**：OPC 客户端读到的 SourceTimestamp 等于接收时刻；缓存重发不会让 SourceTimestamp 前移。
- **单测**：`SnmpValueCodecTest.sourceTimeEqualsReceivedAt`；`SharedPollCoordinatorTest.cachedRedeliveryKeepsBindingInstant`。

### M0-9 SET tooBig 诊断、批次学习、有界缓存（P2，顺延到 B1 之后，Q13）

> 本节不属于 B1 的 M0 范围，已移入路线图（§7 1.1.x）。以下设计保留供后续版本使用。

- **SET tooBig**：保持现有映射 `Bad_EncodingLimitsExceeded`，不重试。诊断文案改为 `agent reported tooBig for single-varbind SET; value exceeds agent message size limit`。单测：`SnmpFailureClassifierTest.setTooBigIsLegalResponse`。
- **R9 学习上限**：DeviceState 增加 `learnedMaxOids`。收到 tooBig 时设为拆分后的大小；连续 N=20 个周期成功后加 1，直到配置值。`selectBatch` 取 `min(配置, learned)`。单测：`SharedPollCoordinatorTest.tooBigLearnsBatchCap`。
- **OMP-D**：不改默认值，只在互通测试中记录 v3 头部的实际开销。
- **N6**：`DynamicAttributeReader.reportedFailures` 设上限 512，满了就清空（与 `SharedPollCoordinator.java:699-704` 一致）。单测：`DynamicAttributeReaderTest` 若因依赖 DeviceContext 难以单测，就把限界逻辑抽成小工具类再测。

### M0 契约调查（P0，先于编码，1–2 人日）

静态调查已完成，提交 `f99b28d`，记录见 [M0S_CONTRACT_FINDINGS.md](research/M0S_CONTRACT_FINDINGS.md)。需要授权的系统实测尚未执行，不阻止整理 M0 实施规格，不能据此视为 M1 验收通过。原调查范围：Milo `SubscriptionModel` 对动态项的采样行为（R6）；8.3 SDK 中模块数据目录的 API（§4.6 持久化）；`DeviceExtensionPoint.validate` 能否读取其他设备的配置（§4.8 冲突校验）；8.3 中 `DeviceContext` 节点批量写的原子性（§4.7）。

---

## 4. M1 Trap/Inform 规格

**术语（Q6）**：以 `CONTEXT.md` 为准。泛指 Trap 与 Inform 时用 **Notification**；**Trap** 指不需确认的 Notification，**Inform** 指需要 ACK 的 Notification；**Trap Listener** 指一个 bind 地址加端口上的接收端点（下文 `listener`、`TrapListener` 均指它）；源地址不属于任何 Device 的称为 **Unmatched Notification**；Device 上暴露的计数加最近一条内容称为 **Notification Snapshot**。面向用户的节点名与配置名保留 `Traps/`、`TRAP.*`、`trap*`，不随术语改名。

### 4.1 范围

| 能力 | B1 |
|---|---|
| v1 Trap（community） | ✅，按 RFC 3584 转换为 v2 形式 |
| v2c Trap / v2c Inform | ✅（Inform 回 ACK） |
| v3 Trap（authNoPriv/authPriv，noAuthNoPriv 仅在设备 Security Level 允许时） | ✅，强制配置发送方 engineID `trapSenderEngineId`（Q4，ADR 0003） |
| v3 Inform | ✅，依赖持久化的本地 engineID/engineBoots |
| MIB 名称翻译 | ❌（D3，显示原始 OID） |
| Notification 归属 | 只归属已配置的 Device；只发 Trap 的设备配置为不带 Tag 的 Device（Q1，ADR 0001） |
| Gateway 级任意来源入口 | ❌（Q1，ADR 0001；Unmatched Notification 只计数） |
| 逐条事件持久化 / Event Streams / SQL | ❌（D5；B1 只提供 Notification Snapshot，逐条审计在路线图，Q2，ADR 0002） |
| 新 Web UI 页面 | ❌（D2） |
| 冗余 Gateway | ❌（Q3：B1 不支持，手册注明；冗余 Gateway 双节点都会监听，行为未定义） |

### 4.2 所有权与引用计数（Gateway 级共享 listener）

```text
ServiceBundle
├── SharedPollCoordinator / Snmp4jRequestClient   （轮询，独立 socket）
└── TrapListenerRegistry                          （Trap，独立 SNMP4J 栈）
    ├── TrapEngineIdentity   （持久化 engineID/boots，Registry 级唯一，见 4.6）
    ├── TrapListener[key=port；bindAddress 由第一个 acquirer 确定]
    │   ├── DefaultUdpTransportMapping + MessageDispatcherImpl + MPv1/MPv2c/MPv3(隔离 USM)
    │   ├── TrapCommandResponder（监听线程：过滤/限速/去重/入队/ACK）
    │   └── refCount、state(BOUND|FAILED|CLOSED)、lastBindError
    ├── 有界队列 ArrayBlockingQueue<TrapEnvelope>(10_000)（Registry 级）
    └── TrapWorker 线程 ×1（出队 → 规范化 → 路由 → 更新 TrapState）
SNMPDevice
└── TrapHandle = registry.acquire(deviceGeneration, ListenerKey, TrapFilter)
```

- **生命周期**：
  - `TrapListenerRegistry` 在 `ServiceBundle.ensureStarted()` 中创建，但**它的任何失败都不会让 `ensureStarted` 抛出**，与 R1 的致命路径完全隔离。
  - 第一个 `acquire` 时 bind；`refCount` 降到 0 时同步 close。
  - 设备 `onStartup`（`trapEnabled` 为真）时 `acquire`，`onShutdown` 与 `cleanupAfterStartupFailure` 时 `release`。设备重配置时先 release 后 acquire，在同一个 listener 锁内串行执行，避免 rebind 竞争。
  - 模块 shutdown 时调用 `registry.closeAll()`。
- **绑定失败**：listener 进入 `FAILED`，记录原因；持有它的设备 `Traps/Status=Degraded`，`Traps/LastError` 按 §4.10 的文案填写。设备主 `Status` 与轮询**不受影响**（D4）。只要还有引用，registry 每 30 s 重试 bind 一次，这样在 Windows 上停掉 SNMP Trap 服务后无需重启就能恢复。
- **listener 键规范化**：bind 地址只接受 IP 字面量（不做 DNS），解析为 `InetAddress` 后与端口一起作为键。`0.0.0.0`、具体 IPv4、`::`（可能是双栈）在同一端口上会互相覆盖，所以保存时规定：**同一端口上所有设备的 bindAddress 必须完全相同**。保存期拒绝见 Q19；运行期 `acquire` 以端口为键，bindAddress 不一致的后来者被拒绝，该设备 `Traps/Status=Degraded`，LastError 写明冲突的 Device 与地址（Q23）。

### 4.3 设备路由与过滤

Notification 只归属已配置的 Device，不提供 Gateway 级「接收任意来源」入口；只发 Trap、不需要轮询的设备配置为不带 Tag 的 Device（Q1，`docs/adr/0001-notifications-belong-to-devices.md`）。

- **TrapFilter**（每个设备一个）：
  - `sources`：设备 host 经 DnsResolver 解析得到的地址集合，加上 `trapSourceAllowlist`（IP/CIDR 列表）。
  - `security`：COMMUNITY（v1/v2c，community 用常量时间比较，`MessageDigest.isEqual`；`trapCommunity` 为空时沿用 `readCommunity`，Q8），或 USM（securityName、发送方 engineID、securityLevel ≥ 设备配置）。
  - 认证失败（源地址属于本设备但 community/USM 不匹配）：计入 `AuthFailureCount`，`Traps/LastError` 提示检查 Trap Community（Q8）。
  - 是否接受 Inform。
- **匹配**：只用 UDP 源地址，**不用** v1 PDU 中的 agent-addr（agent-addr 只作为字段展示）。NAT、代理场景必须实测源地址；allowlist 只接受可见的代理地址，不能恢复原设备区分。多个来源共用代理时可能广播到多个 Device，应优先选择经验证保留源 IP 的部署。
- **多设备命中**：广播到所有命中的设备，各自独立计数（Q7，ADR 0001）。listener 级记录 `MultiMatchCount`，便于排查配置。
- **未命中**：作为 Unmatched Notification 只计 listener 级 `UnmatchedCount` 后丢弃，不归属任何 Device；按源 IP 每分钟最多汇总一条日志；不 ACK Inform。
- **DNS 刷新**：route 表在设备 host 的 DNS 缓存过期（5 min，`DnsResolver.java:25`）后重新解析刷新；解析失败时保留旧地址，并在 `Traps/LastError` 提示。

### 4.4 v1 → v2 转换（RFC 3584 §3.1）

- `snmpTrapOID.0`：generic-trap 为 0–5 时取 `1.3.6.1.6.3.1.1.5.(generic+1)`；为 6 时取 `enterprise.0.specific`。
- 规范化后的 varbind 依次为：`sysUpTime.0`（v1 timestamp）、`snmpTrapOID.0`、原 varbinds、`snmpTrapAddress.0`（`1.3.6.1.6.3.18.1.3.0`，取 agent-addr）、`snmpTrapEnterprise.0`（`1.3.6.1.6.3.1.1.4.3.0`）。
- **不加** `snmpTrapCommunity.0`，避免 community 暴露到 Tag。

### 4.5 v2c Inform ACK 时点与去重

- **不使用** `Snmp.addNotificationListener`（它会在 listener 处理前自动 ACK，见 `J/Snmp.java:2183-2201`）。改为直接组装 `MessageDispatcherImpl` 并注册自有 `CommandResponder`；ACK 仿照 `J/Snmp.java:2213-2227`：克隆 PDU，类型设为 RESPONSE，`errorStatus/errorIndex=0`，调用 `returnResponsePdu`，并带上原事件的 security 信息、`maxSizeResponsePDU`、`stateReference`。处理后 `event.setProcessed(true)`。
- **顺序**（全部在监听线程上完成，每步 O(1)）：
  1. 认证（由 MPv3/USM 完成，或 community 比较）
  2. 路由（至少命中一个设备）
  3. License 检查（§4.9）
  4. 每设备 token bucket 限速
  5. Inform 去重
  6. `queue.offer`（不阻塞）
  7. 入队成功后才 ACK
- 任一步失败：**不 ACK**，计入对应的丢弃计数，发送方会按自身策略重发。
- **语义**（Q9，`docs/adr/0004-inform-ack-after-in-memory-enqueue.md`）：ACK 表示「Gateway 已接受进入内存队列」，**不保证持久化**；进程崩溃时队列中的 Inform 会丢失，手册写明。不承诺 exactly-once。
- **去重**：键为（源地址:端口、版本、community 摘要或 securityName + engineID、contextName、requestID），TTL 60 s，最多 4096 项（LRU）。重复的 Inform **再 ACK 一次**（发送方可能没收到上次的 ACK），但不重复计数、不更新 Tag，只计 `DuplicateInformCount`。

### 4.6 v3 Trap/Inform：本地 engineID、engineBoots、USM 隔离

- **两个方向的权威方不同**（RFC 3414）：v3 Trap 的权威方是**发送方**；v3 Inform 的权威方是**接收方**，即 Gateway。
- **本地引擎身份**：由 `TrapEngineIdentity` 持有，整个 Registry 只有一个（不放在任一 Device 目录，否则删除或重命名设备会改变共享身份）。
  - 文件：`gatewayContext.getSystemManager().getModuleConfigLocalDir("com.jw.snmpdriver").resolve("trap-engine.properties")`，即 `$data/config/local/com.jw.snmpdriver/trap-engine.properties`。调用方创建目录，内容为 `engineId=<hex>`、`engineBoots=<n>`。API 与备份契约见调查记录 §2，原子写能力仍需目标文件系统实测。
  - 启动流程：读取文件，`boots = 旧值 + 1`，用「临时文件 + fsync + `ATOMIC_MOVE`」写回，**写成功后才** `listen`。第一次启动时用 `MPv3.createLocalEngineID()` 生成 engineID，此后不再改变。
  - 写入失败：v3 Inform 停用，相关设备 `Traps/LastError` 写明原因；v1/v2c/v3 Trap 不受影响。
  - 文件损坏：改名为 `.corrupt-<ts>` 备份，生成新的 engineID，记录 warn 日志，提示发送方需重新配置 engineID。
  - `engineBoots` 达到 2147483647 时停用 v3 Inform（RFC 3414 §2.2.2）。
  - boots 只在 Registry（引擎）初始化时递增，**与设备启动或配置保存无关**。
  - config/local 包含在备份中，恢复时可选择是否恢复 local 内容。恢复旧备份可能使 boots 回退，克隆可能复制身份，不能以旧值加一保证超过远端记录。M1c 前必须细化恢复与克隆处理流程，验证恢复 local/不恢复 local 两种情况；不能自动清时间窗缓存绕过防重放。
  - 诊断节点 `Traps/LocalEngineId` 暴露 engineID（只读 hex），方便配置发送方。
- **USM 隔离**：Trap 栈使用独立的 `SecurityProtocols`/`USM`/`MPv3`（做法同 M0-6），与轮询栈互不共享。
- **用户注册**：
  - v3 Trap（Q4，`docs/adr/0003-v3-trap-requires-sender-engine-id.md`）：强制要求设备配置 `trapSenderEngineId`，用 `usm.addUser(user, senderEngineId)` 在注册时按固定 engineID 本地化；旧三参 API 已弃用；**不注册**可以按任意 engineID 现场本地化的用户（否则大量伪造 engineID 会造成 CPU 与内存消耗，相关逻辑见 `J/security/USM.java:349-386`）。已本地化用户的 grant 为 never。若预派生密钥再用六参 UsmUser 构造，auth/priv 参数必须为密钥而非普通口令；不能仅给未本地化通配用户设置 never 就期待自动派生。见调查记录 §5.4。
  - 设备 engineID 变化（换板、复位）后需重新配置 `trapSenderEngineId`。手册给出查询方法：对设备执行 `snmpget` 读取 `snmpEngineID.0`（`1.3.6.1.6.3.10.2.1.1.0`），例如 `snmpget -v 2c -c <community> <device-host> 1.3.6.1.6.3.10.2.1.1.0`，或 v3 下 `snmpget -v 3 -u <user> -l authPriv -a <auth-proto> -A <auth> -x <priv-proto> -X <priv> <device-host> 1.3.6.1.6.3.10.2.1.1.0`，把返回的 hex 填入 `trapSenderEngineId`。
  - v3 Inform：用户本地化到 Gateway 本地 engineID，不受 `trapSenderEngineId` 影响。
- **冲突**：同一 listener 上相同 securityName 的认证在路由之前就完成，无法靠源 IP 区分。因此同一 listener 上同名 securityName 必须使用**同一** SecretConfig 引用和相同协议，否则保存时报错；运行期再做一次 plaintext 比对，不一致的设备 `Traps/Status=Degraded`。
- **时间窗**：认证路径由 `UsmTimeTable` 跟踪。远端 boots 增大可更新，回退拒绝；相同 boots 下超窗旧包拒绝，但窗口内重复包可能通过，不能当逐包去重。Inform 的本地权威时间窗与 Trap 的远端权威时间窗分开验收，noAuthNoPriv 不套用认证防重放保证；厂商互通仍待实测。见调查记录 §5.3。

### 4.7 OPC 节点模型（快照与逐条事件的语义边界）

**语义边界（手册必须原文写入；Q2，`docs/adr/0002-notification-snapshot-semantics.md`）**：B1 暴露的是 **Notification Snapshot（状态快照 + 计数器）**，**不是逐条事件队列**。两次 Tag 采样之间到达的多条 Notification，只有计数器能反映次数，`Last*` 只保留最后一条，中间的状态（例如 linkDown 紧接 linkUp）可能完全看不到。递增计数也不能保证「每条 Trap 对应一条可单独确认的报警」。需要逐条审计时，等路线图中的 Event Streams 或 SQL TrapStore。

**真实节点**（`[Device]/Traps/`，由 `TrapDiagnostics` 创建，写法参照 `DeviceDiagnostics.java:59-81`）：

| 节点 | 类型 | 含义 |
|---|---|---|
| `Status` | String | `Disabled` / `Listening` / `Degraded` / `Unlicensed` |
| `LastError` | String | 最近一次 Trap 相关错误，含处置建议 |
| `ListenerEndpoint` | String | 如 `0.0.0.0:10162` |
| `LocalEngineId` | String | v3 Inform 用的本地 engineID（hex） |
| `ReceivedCount` | UInt64 | 路由到本设备的 PDU 总数（含被丢弃的） |
| `AcceptedCount` | UInt64 | 入队并处理成功的数量 |
| `DroppedRateLimitCount` / `DroppedQueueFullCount` / `DroppedUnlicensedCount` | UInt64 | 各类丢弃 |
| `AuthFailureCount` | UInt64 | 源地址属于本设备但 community/USM 不匹配 |
| `DuplicateInformCount` | UInt64 | 重复的 Inform |
| `ListenerUnmatchedCount` / `ListenerMultiMatchCount` | UInt64 | listener 级共享计数（只读镜像） |
| `Last/Sequence` | UInt64 | 每接受一条加 1；**客户端应以它的变化作为触发依据** |
| `Last/ReceivedTime` | DateTime | Gateway 接收时间 |
| `Last/PduType` | String | `V1_TRAP` / `V2_TRAP` / `INFORM` |
| `Last/Version` | String | `V1` / `V2C` / `V3` |
| `Last/TrapOid` | String | 原始数字 OID（D3） |
| `Last/Source` | String | `ip:port` |
| `Last/AgentAddress` | String | 仅 v1 |
| `Last/AgentUpTime` | UInt32 | `sysUpTime.0` 的 TimeTicks 原值 |
| `Last/Varbinds` | String | JSON 有序数组 `[{"oid","type","value"}]`，最大 16 KiB，超出时截断并加 `"truncated":true` |

- **一致性**：`Last/*` 为非原子镜像，相同 sourceTime 不提供跨节点事务或通知顺序保证。撤回单纯 Sequence 双读保证一致性的算法。M1b 编码前需确定一致读取契约，候选为一个 Read 请求固定一次不可变 Snapshot 引用，或新增包含 Sequence 的单值快照；这些候选尚未批准，不视为新增节点承诺。跨多次 Read 仍可能看到不同快照。现有 Sequence 每条加一与 Q2 快照语义不变。见调查记录 §4。
- **动态路径（有界，Q10 保留）**：在 `ItemPathParser` 中，访问前缀 `TRAP` 走独立分支（当前 `:41-48` 遇到非 RO/RW 前缀会报错，扩展时不影响既有语法）：
  - `TRAP.Count/<trapOID>`（UInt64）
  - `TRAP.LastTime/<trapOID>`（DateTime）
  - `TRAP.LastVarbinds/<trapOID>`（String JSON）
  - `TRAP.Sequence/<trapOID>`（UInt64）

  这些路径只读，**不触发 SNMP GET**，也不受 1000 ms 轮询下限约束；SubscriptionBridge 识别 `TRAP.` 前缀后改为订阅 TrapState 并在 Trap 到达时推送。每个设备最多跟踪 256 个 trapOID；已被订阅的 OID 优先保留；超出上限计入 `Traps/OverflowCount`，未跟踪的 OID 读到 `Count=0` 且质量为 Good。不提供 `TRAP/<oid>/<varbind>` 路径（避免类型歧义和无界 NodeId）。
- **Alarm 指引**：在 `Last/Sequence` 或 `TRAP.Count/<oid>` 上用 Expression/脚本判断「值变化」，同时说明它的局限。不承诺一条 Trap 对应一条报警，也不给出未经实际验证的 Alarm 配置作为契约。

### 4.8 配置字段（`SNMPDeviceConfig` 新增 `TRAP` 分类）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `trapEnabled` | boolean | `false` | 关闭时不 acquire listener |
| `trapBindAddress` | String | `0.0.0.0` | 只接受 IP 字面量 |
| `trapPort` | int | `162` | D4 |
| `trapSecurity` | enum `COMMUNITY`/`USM` | `COMMUNITY` | USM 沿用设备 V3_SECURITY 字段（R15） |
| `trapCommunity` | SecretConfig，可空 | null | 为空时沿用 `readCommunity`（Q8） |
| `trapSenderEngineId` | String（hex） | `""` | v3 Trap 强制必填，5–32 字节（Q4，ADR 0003）；未配置则不接收 v3 Trap，v3 Inform 不受影响 |
| `trapAcceptInform` | boolean | `true` | |
| `trapSourceAllowlist` | String | `""` | 逗号分隔的 IP/CIDR，用于追加源地址 |
| `trapRateLimitPerSecond` | int | `100` | 每设备可调，1–10000；burst = 2×，默认 200（Q11） |

模块常量（不开放配置，避免同一 listener 上的参数冲突）：队列容量 10 000；每条 Trap 最多 128 个 varbind；PDU 最大 65 535 B；worker 1 个；去重 TTL 60 s。

`SNMPDeviceConfig` 是 record，新增字段会改变 `defaults()`（`SNMPDeviceConfig.java:59-66`）和资源 schema。

**升级兼容（Q12，硬性要求，M1 退出条件）**：1.0.0 的设备配置升级到 1.1.0 后**不改即可加载**；缺少的 `trap*` 字段取默认值，Trap 默认关闭（`trapEnabled=false`），轮询行为不变。M0-S 确认 record 缺字段只得 0/false/null，不自动调用业务 defaults。M1a 在 decode 前仅为缺失的新 trap* 字段补默认值，再沿用原解码器与 SecretConfig adapter；显式非法值保留并交给校验，不能覆盖旧轮询配置；验收方式是在 igdev Gateway 导入 1.0.0 配置做升级测试（§6.3）。

**保存时校验**（`SNMPDeviceExtensionPoint.validate`，现有代码 `:75-117`）：

- 单设备：端口范围、IP 字面量、engineID hex 格式与长度、allowlist 语法、限速范围；`trapSecurity=USM` 时要求设备 version 为 V3；COMMUNITY 模式下 trapCommunity 与 readCommunity 至少有一个。
- 跨设备（D4）：同一端口上 bindAddress 必须完全相同；同一 listener 上同名 USM securityName 必须使用相同的 SecretConfig 引用和协议。ServiceBundle 只保存运行时 Device，不是包含禁用或创建失败设备的完整配置集合。保存期跨设备拒绝需要可识别当前资源的配置快照/资源层入口（避免编辑自身误报、递归解码，覆盖禁用资源、并发保存和批量导入），该入口由独立 spike 验证，不再是 M1a 的前置条件，见 Q19 与 ADR 0007；`registry.acquire` 运行期再做一次相同检查，冲突的设备 `Traps/Status=Degraded`，LastError 写明冲突的对象。

### 4.9 License 行为（Q5）

- listener 的 bind 不依赖 License，以免 License 状态抖动导致端口反复开关。
- 每条入站 PDU 在路由后检查 License，复用 `LicensedSnmpRequestClient` 的缓存判定逻辑（`:68-97`）。需抽出一个无副作用的 `isLicensed()`/`licenseStatusCode()` 方法供 Trap 共用。
- 无有效 License 时：端口照开；不 ACK Inform，不入队，不更新 `Last/*`；计入 `DroppedUnlicensedCount`；`Traps/Status=Unlicensed`；`Last/*` 与 `TRAP.*` 节点质量置为 `Bad_LicenseNotAvailable` 或 `Bad_LicenseExpired`，与轮询一致（commit `560c9c2` 的语义）。
- License 恢复后自动接收，不需要重启。

### 4.10 端口策略（D4，`docs/adr/0005-trap-port-policy.md`）

| 环境 | 推荐做法 |
|---|---|
| 默认 | `trapPort=162`。bind 失败时只有 Trap 功能 Degraded，轮询照常 |
| Linux（首推） | 模块配置 `trapPort=10162`，在 nftables 中把 162 重定向到 10162（见下）。**不推荐** `setcap cap_net_bind_service`（权限作用于整个 JVM）|
| Windows | 先检查 `SNMPTRAP` 服务是否存在、运行及 UDP 162 的地址/PID，再由运维按授权停用冲突服务并放行 UDP 162 入站 |
| Docker / igdev | `-p 162:10162/udp`，模块配置 10162，发送方发往宿主机 162 |

nftables 示例（运维侧执行，模块不负责）：

```text
table inet snmptrap {
  chain prerouting { type nat hook prerouting priority dstnat; udp dport 162 redirect to :10162 }
  chain output     { type nat hook output     priority -100;   udp dport 162 redirect to :10162 }
}
```

`prerouting` 处理外部流量，`output` 处理本机发往 162 的流量（便于本机测试）。REDIRECT 保留源 IP，所以源地址路由仍然有效。Docker 的 userland-proxy 和 rootless 网络可能改写源 IP，导致路由失败（**待验证**），allowlist 不能恢复原 IP；应先验证保留源地址的网络方案，host 网络限定目标平台且实测，rootless 的宿主机 162 仍受低端口权限限制。

`Traps/LastError` 文案模板：

```text
Trap listener bind <addr>:<port> failed: <OS 原因>.
Linux: set Trap Port to 10162 and redirect UDP 162→10162 with nftables;
Windows: stop/disable the "SNMP Trap" service;
Docker: publish -p 162:10162/udp and set Trap Port to 10162.
```

### 4.11 安全

- community 和 USM 口令走 Secret，不写入日志，不出现在 Tag 中（包括 `snmpTrapCommunity`）。
- 日志：源地址、OID 去除控制字符后截断到 256 字符；未命中/认证失败按源每分钟汇总。
- 资源上限：PDU 大小、varbind 数、JSON 长度、去重表、per-source 表（listener 级 per-source token bucket，最多 4096 个源，LRU）。
- noAuthNoPriv 的 v3 Trap 只有在设备 `securityLevel=NO_AUTH_NO_PRIV` 时才接受。
- 不注册可以按任意 engineID 本地化的 USM 用户（§4.6）。
- Trap 通道不提供任何写操作或命令能力。

### 4.12 M1 关键改动文件

- 新增：
  - `S/snmp/trap/TrapListenerRegistry.java`、`TrapListener.java`、`TrapCommandResponder.java`、`TrapEngineIdentity.java`
  - `S/snmp/trap/TrapFilter.java`、`TrapEnvelope.java`、`TrapEvent.java`（record：sequence、接收时间、版本、PDU 类型、源、有序 varbinds）
  - `S/snmp/trap/V1TrapNormalizer.java`、`TokenBucket.java`、`InformDeduplicator.java`
  - `S/gateway/device/TrapDiagnostics.java`、`TrapState.java`
- 修改：
  - `S/gateway/service/ServiceBundle.java`（Registry 生命周期）
  - `S/gateway/device/SNMPDeviceConfig.java`、`SNMPDeviceExtensionPoint.java`、`SNMPDevice.java`
  - `S/gateway/device/SubscriptionBridge.java`、`DynamicAttributeReader.java`（处理 `TRAP.` 路径）
  - `S/snmp/address/ItemPathParser.java`、`ParsedItemPath.java`
  - `S/snmp/runtime/LicensedSnmpRequestClient.java`（抽出 License 判定）
  - `gateway/src/main/resources/com/jw/snmpdriver/gateway/device/SNMPDevice.properties`（字段文案）
- 文档（Q18）：agent 只更新 Markdown（`docs/SNMP_DRIVER_FEATURE_LIST.md`、`README.md`），另写 `docs/USER_MANUAL_1.1.0_CHANGES.md` 列出用户手册 `docs/SNMP Driver - App&Project Engineer - v1.docx` 需改的内容（M1d 交付物）；`.docx` 由人工更新。`gradle.properties` 中 `moduleVersion` 改为 `1.1.0`。
- 依赖：无新增（SNMP4J 3.12.2 已具备所需 API）；不改 `web-ui`（D2）。

---

## 5. 里程碑、工期、风险与待验证项

### 5.1 里程碑与重估（单人人日；静态评估区间，不是承诺）

| 里程碑 | 内容 | 人日 |
|---|---|---|
| M0-S | 静态契约调查已完成，见 `f99b28d`；系统实测留对应里程碑 | 1–2 |
| M0 | M0-1…M0-8（P0 全部 + R2、R7、R8、N2，Q13） | **8–12** |
| M1a | Registry、listener 所有权/引用计数、bind 降级与重试、配置字段（含 1.0.0 配置升级兼容，Q12）、校验 | 3–4 |
| M1b | v1/v2c Trap、v2c Inform ACK/去重、v1→v2 转换、路由、限速/队列、`Traps/` 节点、`TRAP.*` 路径、License | 4–5 |
| M1c | v3 Trap/Inform、engine 持久化、USM 隔离、冲突检查 | 3–4 |
| M1d | net-snmp 系统测试、1.0.0→1.1.0 升级测试（Q12）、风暴测试、Feature List/README 更新与 `docs/USER_MANUAL_1.1.0_CHANGES.md`（Q18）、端口指引、发布 1.1.0 | 2–3 |
| **合计 B1** | | **约 21–30** |

重算说明（Q13）：M0-9（原 M0-P2，1–2 人日）顺延到 B1 之后、移入路线图（§7），不再计入 B1；原「含 P2 为 22–32」的区间作废。

该区间与 Codex 给出的「加固 + 可发布 A：22–30 人日」一致。v1 中「A 14–18 人日」**不再采用**。

**退出条件**：
- M0：§3 中 M0 范围（Q13）各项验收全部通过，且 `./gradlew :gateway:test` 通过（worker 已获长期授权运行，Q16）。
- M1：§6 系统测试矩阵全部通过，风暴测试达到 §6.4 的阈值；**硬性条件**：1.0.0 设备配置在 igdev 导入并升级到 1.1.0 后不改即可加载，Trap 默认关闭，轮询不变（Q12）。系统测试与风暴测试每次执行前需询问用户（Q16）。

### 5.2 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| R2 改为异步后改变回调时序，引发回归 | 轮询值乱序或丢失 | 每设备 SerialExecutor；M0-1 先补测试 |
| 新增 record 字段对旧配置的兼容性 | 升级后设备无法加载 | Q12 硬性要求，列为 M1 退出条件；igdev 上导入 1.0.0 配置做升级测试 |
| v3 与厂商实现的互通（engineID、时间窗、算法） | v3 Trap/Inform 不可用 | 用 net-snmp 交叉验证；手册列出已验证的组合 |
| Docker/rootless 改写源 IP | 路由失败，Notification 被记为 Unmatched Notification | 优先验证保留源 IP 的部署；allowlist 只能接受代理地址，不能恢复设备隔离 |
| 跨设备校验看不到禁用设备 | 冲突配置启动后才暴露 | 运行期复查并降级，LastError 写明 |
| 快照语义被误解为逐条事件 | 现场漏报警 | 手册原文写明边界；路线图 Event Streams/SQL |
| 冗余 Gateway | 双节点都接收，重复计数 | B1 不支持，手册注明（Q3） |

### 5.3 调查结论与剩余实测

原十项已由 [M0-S 调查记录](research/M0S_CONTRACT_FINDINGS.md) 覆盖。下列条目保留为原调查索引，不能把它们全部视为未调查或已经实测。静态结论以该记录各节为准；待执行项、授权边界见记录末尾清单。M1a 资源校验入口、M1b 一致快照读取契约、M1c 恢复/克隆身份流程需在对应里程碑编码前落定。

### 5.3.1 原调查索引

1. Milo `SubscriptionModel` 对动态 DataItem 是否额外调用 `read()`，以及是否覆盖质量码（R6）。
2. 8.3 SDK 获取模块数据目录的 API，以及该目录在备份恢复中的行为。
3. `DeviceExtensionPoint.validate` 能否访问其他设备的配置（包括禁用的设备）。
4. 8.3 中设备节点批量 setValue 的原子性和通知顺序。
5. SNMP4J 3.12.2：隔离 MPv3 后是否还有路径隐式使用全局单例（M0-6）；多个并发请求共用 MPv3 engine 缓存的行为；`UsmTimeTable` 在发送方重启、boots 回退、重放时的行为；为本地化用户设置 `LocalizationGrant.never` 的 API；SNMP4J 解码能否产生 `NumericOid.parse` 拒绝的 OID（R3 远端触发路径）。
6. `SNMPDeviceConfig` 新增字段后旧配置的迁移机制（结果须满足 Q12 硬性要求，见 §4.8）。
7. Docker 端口映射（含 userland-proxy、rootless）是否保留 Trap 源 IP。
8. Windows SNMP Trap 服务的确切服务名和占用方式（不同 Windows 版本可能不同）。
9. v3 默认头部开销对 `maxRequestBytes=1200` 的实际影响（OMP-D）。
10. R8 修复前后的 CPU 基准。

### 5.4 交付流程（Q17）

- **粒度**：每个里程碑（M0-S、M0、M1a、M1b、M1c、M1d）对应一个 GitHub Issue、一个分支、一个 PR；不直接 commit `main`。
- **Issue/PR**：开 Issue 或 PR 前先询问用户；Issue 操作约定见 `docs/agents/issue-tracker.md`。
- **分工**：Opus 5.5（high）实现；codex gpt-6.1-sol（high）交叉 review；coordinator 统筹验收。
- **验收**：PR 合并前满足 §5.1 对应里程碑的退出条件；Verification 授权范围见 §6（Q16）。

---

## 6. 测试策略

> **执行环境（Q15）**：以 Linux 环境为准，repo root 为 `/home/sheon/Projects/snmp-driver`，Gradle 命令使用 `./gradlew`。所有命令从 repo root 执行。命令中的口令与 community 用占位符表示，实际值不写入仓库或文档。
>
> **Verification 授权（Q16）**：
> - 长期授权：worker 可在 repo root 直接运行 `./gradlew :gateway:test`、`./gradlew :base:test`、`./gradlew build`。
> - 逐次询问：igdev Gateway 系统测试（§6.3，含 1.0.0 配置升级测试）、Docker 操作、风暴测试（§6.4）、`web-ui` 相关命令，每次执行前都必须询问用户。
> - 其余未列出的 Verification 仍按 AGENTS.md「Verification 规则」先获用户授权。

### 6.1 单测（gateway/src/test）

- M0：§3 各项列出的测试类。
- M1：
  - `V1TrapNormalizerTest`（generic 0–5、enterpriseSpecific、不添加 community）
  - `TrapFilterTest`（源地址、CIDR、community 常量时间比较、securityLevel 下限）
  - `TokenBucketTest`
  - `InformDeduplicatorTest`（TTL、容量、重复时再 ACK 但不计数）
  - `TrapEngineIdentityTest`（首次生成、boots+1、原子写失败时停用 v3 Inform、损坏文件备份、上限）
  - `TrapListenerRegistryTest`（引用计数、最后一个 release 时关闭、bind 失败只降级 Trap、重试、同端口不同 bindAddress 冲突、USM 同名冲突）
  - `ItemPathParserTest`（`TRAP.*` 分支，原有语法不回归）
  - `TrapStateTest`（Sequence 单调递增、256 上限、已订阅 OID 优先、JSON 截断）
  - License 判定（未授权不 ACK、不入队）

### 6.2 SNMP4J 进程内集成测试

- 在 loopback 随机高端口启动 Registry，用 SNMP4J 发送 v1/v2c Trap、v2c Inform、v3 Trap、v3 Inform，断言 ACK 时点（队列满时不 ACK）、去重和计数。
- 设置 IPv6 mapping 失败的注入点，断言模块仍可轮询（R1）。
- 构造轮询客户端与 Trap 栈后，断言全局 `SecurityModels` 没有被修改（R7）。
- 注意：同一个库自发自收**不能证明互通**，只作为回归测试。

### 6.3 net-snmp 系统测试（igdev Gateway，Docker `-p 162:10162/udp`，模块 `trapPort=10162`）

```text
# v2c Trap
snmptrap -v 2c -c <community> <gw-host>:162 '' 1.3.6.1.6.3.1.1.5.3 1.3.6.1.2.1.2.2.1.1.1 i 1
# v1 Trap（enterpriseSpecific）
snmptrap -v 1 -c <community> <gw-host>:162 1.3.6.1.4.1.8072.2.3 <agent-ip> 6 17 '' 1.3.6.1.2.1.1.5.0 s test
# v2c Inform
snmpinform -v 2c -c <community> <gw-host>:162 '' 1.3.6.1.6.3.1.1.5.4 1.3.6.1.2.1.2.2.1.1.1 i 1
# v3 Trap（发送方 engineID 需与设备 trapSenderEngineId 一致）
snmptrap -v 3 -e <sender-engine-id-hex> -u <user> -l authPriv -a SHA-256 -A <auth> -x AES -X <priv> <gw-host>:162 '' 1.3.6.1.6.3.1.1.5.1
# v3 Inform（接收方 engineID 由 discovery 获得，可与 Traps/LocalEngineId 核对）
snmpinform -v 3 -u <user> -l authPriv -a SHA-256 -A <auth> -x AES -X <priv> <gw-host>:162 '' 1.3.6.1.6.3.1.1.5.1
```

测试矩阵：

- 版本 × 安全等级 × auth/priv 算法组合（至少 MD5/SHA1/SHA-256/SHA-512 × DES/AES128/AES256）。
- 错误 community、口令、engineID。
- Gateway 重启后 v3 Inform 继续可用（boots 递增）；发送方重启。分别覆盖恢复/不恢复 local、旧备份 boots 回退、克隆身份、窗口内重复与超窗旧包，不用清缓存规避重放校验。
- 端口：被占用时 Degraded 且轮询正常；释放后在 30 s 内自动恢复。
- 两个设备共享 listener；同端口不同 bindAddress 时保存报错。
- 设备禁用后再启用；模块重载。
- License 失效与恢复。
- 未匹配源（Unmatched Notification 只计数）；多个 Device 匹配同一源时广播（Q7）。
- Docker 映射下源 IP 是否保留。
- **升级测试（Q12，M1 退出条件）**：在 igdev Gateway 导入 1.0.0 设备配置，升级模块到 1.1.0，断言配置不改即可加载、`trapEnabled=false`、轮询结果与升级前一致。

### 6.4 风暴测试

- 负载：50 设备 × 25 OID、1 s 轮询基线，同时注入 2 000 trap/s，持续 5 分钟；再用 10 000 trap/s 突发 30 秒。
- 通过阈值（建议值，发布前由负责人确认）：
  - 轮询 p95 延迟相对基线增幅 < 20%，无新增轮询超时；
  - 所有未被接受的 Trap 都能在各类丢弃计数中对上账；
  - 堆内存在队列上限内保持平稳；
  - Inform 在队列满时不 ACK。

---

## 7. B1 之后路线图（D5）

| 版本 | 内容 | 前置条件 |
|---|---|---|
| 1.1.x | 原 M0-9（SET tooBig 诊断、R9 批次学习上限、N6 有界拒绝缓存、OMP-D 记录；Q13 顺延）、R4 修订策略（钳制或拒绝）、R5 异步/并行写入、R10 License POST 改 WRITE（保留 CSRF）、R12 有限重试、N4 DNS single-flight 与多地址回退、R6 动态项发布所有权专项（完整覆盖 create/modify/delete/restore/mode-change）、按需单次读/maxAge | 负责人决策（R4、R10） |
| 1.2 | GETNEXT/GETBULK/Walk（经过 License、并发预算和熔断，不直接持有 Snmp 调用 TreeUtils）、数字 OID 有界发现快照 | — |
| 1.3 | MIB 离线字典加载（见 `docs/adr/0006-defer-mib-parsing.md`）、Trap 名称/enum 翻译、类型推断 | 字典 schema |
| 1.3+ | Table 快照、OPC Browse（基于发现快照、分页，不在 Browse 线程上同步 walk） | SDK Browse 契约调查 |
| 1.4 | 多 varbind SET、RowStatus 命令 API（明确幂等语义与结果未知，见 N5） | 命令 API 设计 |
| 独立评估 | 逐条 Notification 审计（Q2，ADR 0002）：Event Streams 适配（建议做成可选的独立模块，主 Hook 不直接引用其类）、SQL TrapStore（有界异步写入、保留期） | 逐条事件需求确认；客户环境安装情况 |
| 需改规则 | 任何新增 Web UI 页面（Trap 查看、MIB 管理） | 修改 AGENTS.md 中「只允许 `page/license`」的规则，并另行授权（无对应仓库 ADR，沿用 AGENTS.md 现有规则） |
| 未排期 | Gateway 级独立 Trap Receiver（接收未配置来源，方案 C；B1 不做，Q1，ADR 0001）、冗余协调（Q3）、3DES/AES 变体、SNMP over TCP/TLS/DTLS、写专用 v3 用户 | 需求确认 |
