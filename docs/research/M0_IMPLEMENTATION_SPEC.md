# M0 实施规格草稿

状态：本地草稿，尚未发布 GitHub Issue。M0-S 对应 Issue #2，调查提交为 `f99b28d`。本文件供下一里程碑建 Issue 使用，不代表已获创建 Issue/PR 的授权。

## 目标与边界

为 1.1.0 的 Trap/Inform 建立可靠轮询基线，落实 v2 方案 M0-1 至 M0-8。实施以当前源码、[修订后的 v2 方案](../SNMP_DRIVER_V2_PLAN.md) 和 [M0-S 记录](M0S_CONTRACT_FINDINGS.md) 为依据。

不实现 Trap、MIB、Walk、Web UI、SET 异步化、M0-9，也不修改 License Web 路由权限。保持现有动态路径、1000 ms 下限、License 质量码与 1.0.0 配置结构。R6 的动态发布所有权变更单列后续专项；M0 只处理时间戳并保留现有发布结构。

M0 使用独立分支和一个 PR，基于合入 M0-S 与方案修订后的 main。不得把用户已有的 AGENTS.md、.gitignore、docs/agents/、igdev.toml、.worktreeinclude 改动夹带提交。

## 工作顺序与可观察验收

| 顺序 | 工作 | 验收证据 |
| --- | --- | --- |
| 1 | 补 gateway 测试基线，使用 fake client 与可控时钟/调度，先覆盖现有轮询与 License 行为 | 能观察实际请求、值推送、资源关闭和失败分类；避免依赖真实网络或长时间 sleep |
| 2 | 修复 HALF_OPEN 探测未完成：非网络结果、空批次、取消与无在途任务均有退出路径 | License 失败后恢复，实际重新发 GET；覆盖 GET/SET 探测及 HALF_OPEN 中新增订阅；不增加网络退避等级 |
| 3 | IPv6 可选与初始化资源清理；AUTO 在 IPv6 不可用时优先 IPv4 | 注入 IPv6 创建失败后 IPv4 可用；后续初始化失败时已创建 mapping 关闭；仅 IPv6 目标明确降级 |
| 4 | 区分启动失败与运行期 Errored | 启动失败保持 Errored；运行期异常后合法响应恢复；关闭/旧 generation 回调不恢复设备 |
| 5 | 有界异步回调，保持同设备顺序 | 业务处理离开响应完成线程；同设备串行、跨设备可并行；饱和拒绝不阻塞接收线程、不遗失完成/清理路径 |
| 6 | 实例化 SecurityProtocols/USM/MPv3 | 全局表中的既有对象未被覆盖或移除，本模块 USM 未注册到全局；两客户端隔离，进程内 v3 GET 可用 |
| 7 | 有界本地化密钥缓存，每请求独立 target | 热缓存减少派生调用；generation、用户、协议、engineID 不串用；覆盖并发发现、内部 REPORT 重试、engineID 更换与在途旧请求 |
| 8 | 传递接收时间戳 | sourceTime 等于 RawBinding.receivedAt；缓存重发和降级旧值保留 sourceTime，serverTime 反映发布；未订阅读不发送 GET |

每项的改动位置与详细用例沿用 v2 §3。只在必要处引入 package-private 注入点或纯 Java 辅助类，不为测试增加无关抽象。

## M0-S 对实施的约束

- 不把 NumericOid 的手造越界输入测试称为 UDP 远端复现。当前解码链的该触发假设已撤回；运行期恢复仍必须实现。
- 安全隔离限于实例安全模型和不污染全局注册表。SNMP4J 仍存在全局尺寸估算/计数器读取，不声称全库完全隔离。
- 密钥失效不能只依赖业务层收到 REPORT 异常。实际 engineID 变化必须重新选键；不承诺“一次 REPORT 必然恢复”。缓存清理不得让同址旧在途请求污染新 generation。
- 4 线程/4096 共享队列之外，每设备 SerialExecutor 也必须有可说明的容量边界。不能用无界设备队列绕过总体上限；拒绝、shutdown 和 shutdownNow 都需完成失败通知并释放在途预算。
- Milo 的动态 read 读取缓存，未发现额外 GET 或必然覆盖质量码。保留时间戳回归检查，最终客户端通知顺序仍需另行授权 Gateway 实测。
- 保持 maxRequestBytes=1200。它是批次估算预算，不能宣称编码后硬上限；CPU 性能收益需后续基准，不以未测百分比交付。

## 验证与交付

此前 Q16 已授权 worker 在 `/home/sheon/Projects/snmp-driver` 运行 `./gradlew :gateway:test`、`./gradlew :base:test`、`./gradlew build`。按改动选择必要检查，至少满足 M0 退出条件 `:gateway:test`。igdev Gateway、Docker、风暴、web-ui 及其他未列出的 Verification 仍需逐次授权。

PR 应记录实际运行的命令和结果、未执行项及原因。fake/进程内测试通过不能替代禁用 IPv6 主机或真实 Gateway 的系统验收。M0 不宣称 B1 可发布。

M0-S 调查与方案修订先提交 PR；M0 Issue 获准发布并认领后进入实施。沿用已约定的实现与交叉 review 分工；若原定实现 provider 不可用，按用户已明确授权的替代范围派发，不默默更换。

## M1 编码前尚需决定

这些问题不阻止 M0 实施，但不能把 M1 规格标为全部可执行。

| 里程碑 | 需要落定的设计 | 保持的要求 |
| --- | --- | --- |
| M1a | 能识别当前资源、包含禁用配置、避免递归解码的保存期校验入口 | D4 保存拒绝冲突；运行期 acquire 复查；不能以 Device 注册表替代配置集合 |
| M1b | 一次 Read 固定不可变 Snapshot，或带 Sequence 的单值快照等一致读取契约 | Q2 快照语义；Last/* 为非原子镜像；新增节点需明确批准 |
| M1c | 恢复 local、旧备份和克隆时的 engineID/boots 流程 | 不靠清时间窗缓存规避防重放；保留写失败时停用 Inform |

M1a 还必须在 decode 前只为缺失的 trap* 字段补齐默认值，保留原配置和 Secret adapter；Q12 的 1.0.0 升级实测仍为 M1 退出条件。
