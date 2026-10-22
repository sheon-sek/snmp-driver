# M0 最终合并验证

日期：2026-10-19。工作树为 `/home/sheon/orca/workspaces/snmp-driver/m0-integration`，分支为 `m0/integration`。

## 来源与合并

已读取 authoritative `/home/sheon/Projects/snmp-driver/AGENTS.md`、其 `docs/agents/domain.md` 和 `docs/agents/issue-tracker.md`，以及当前工作树的 `CONTEXT.md`、相关 ADR 和 `docs/research/M0S_CONTRACT_FINDINGS.md`。已通过 gh 读取 [父规格 #4](https://github.com/sheon-sek/snmp-driver/issues/4) 与 [时间戳任务 #12](https://github.com/sheon-sek/snmp-driver/issues/12) 的正文、标签和评论。历史检索已在合并提交和测试前完成；项目与个人 approved-knowledge 检索均无相关结果，当前实现以来源提交、源码及本轮测试结果为准。

初始 HEAD 为 `c0ebb3bb0fcb080b9c23872f958d904b8b21df1d`，已跟踪文件干净，只有预存未跟踪 `.omp/`。按授权顺序执行两次 `--no-ff` 合并：

| 来源 | accepted source tip | canonical integration merge |
| --- | --- | --- |
| #5–#11 staging | `201e2b2e25c9fcd6c73c3d81aec3b0798c6558a0` | `c6ceb3fc09bf53c629cfbf9d6a56edc10352bae4` |
| #12 接收时间戳 | `1d535f261038cc0460005f9b65fafc914fac49fb` | `5b0441ce2724396997c563f58c45a45e33b098d2` |

两个 accepted tips 均已通过祖先关系检查。两次合并由 Git ort 自动完成，没有文本冲突，也没有手工生产代码或测试修正。`SharedPollCoordinatorTest` 保留 staging 的全部测试与辅助方法，包括 #9 的异步回调覆盖、latch 辅助方法和 endpoint 重载，并增加 #12 的 `cachedRedeliveryKeepsBindingInstant` 及指定接收时刻的 binding 重载。`SnmpValueCodec.java`、`SnmpValueCodecTest.java`、`DeviceDiagnosticsTest.java` 与 #12 accepted source 内容完全一致。

## 授权测试

从本工作树根目录执行，使用 bash，未修改 gradlew 的执行权限：

```sh
bash /home/sheon/orca/workspaces/snmp-driver/m0-integration/gradlew :gateway:test :base:test -x :web-ui:viteBuild
```

测试对应代码 HEAD 为 `5b0441ce2724396997c563f58c45a45e33b098d2`。结果为 `BUILD SUCCESSFUL in 22s`，9 个 actionable tasks 中 5 个执行、4 个 up-to-date。

- 实际执行：`:gateway:compileJava`、`:gateway:compileTestJava`、`:gateway:test`、`:base:compileTestJava`、`:base:test`。
- UP-TO-DATE：`:base:compileJava`、`:common:compileJava`、`:web-ui:jar`、`:gateway:processResources`。
- 输出中还包含 classes 聚合任务与 NO-SOURCE 任务；`:web-ui:viteBuild` 已明确排除，没有运行 UI 构建。

以下计数直接汇总本工作树 `gateway/build/test-results/test/TEST-*.xml` 和 `base/build/test-results/test/TEST-*.xml`。gateway XML 时间为 `2026-10-19T08:12:29` 至 `08:12:42`，base 为 `08:12:43`，两模块 test 任务均在本轮实际执行。

| 模块 / 测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| gateway / DeviceDiagnosticsTest | 1 | 0 | 0 | 0 |
| gateway / DeviceStatusMachineTest | 13 | 0 | 0 | 0 |
| gateway / CallbackDispatchTest | 8 | 0 | 0 | 0 |
| gateway / DeviceCircuitBreakerTest | 1 | 0 | 0 | 0 |
| gateway / HalfOpenRecoveryTest | 25 | 0 | 0 | 0 |
| gateway / SharedPollCoordinatorTest | 16 | 0 | 0 | 0 |
| gateway / Snmp4jKeyCacheTest | 11 | 0 | 0 | 0 |
| gateway / Snmp4jRequestClientTest | 9 | 0 | 0 | 0 |
| gateway / Snmp4jSecurityIsolationTest | 6 | 0 | 0 | 0 |
| gateway / SnmpValueCodecTest | 1 | 0 | 0 | 0 |
| **gateway 合计** | **91** | **0** | **0** | **0** |
| base / LicenseAccessSnapshotTest | 2 | 0 | 0 | 0 |
| base / LicenseRequirementTest | 4 | 0 | 0 | 0 |
| base / AbstractRuntimeServiceTest | 4 | 0 | 0 | 0 |
| **base 合计** | **10** | **0** | **0** | **0** |

Gradle 输出包含 `/usr/lib/jvm/openjdk-17` 探测路径没有 java executable、deprecated API 与 Gradle 9.0 兼容性提示，本次测试正常通过。

## 交付边界

测试后仅新增本验证文档。没有额外重构或扩大生产行为；没有运行完整 build、UI、Gateway、Docker 或系统验证，进程内测试不代表真实 Gateway 或外部 SNMP 设备互通已经验收。未 push，未编辑 Issue/PR，后续发布与 tracker 更新由协调者负责。

未修改、暂存或提交 `.omp/`、本地 policy 或 config，未删除文件；原未跟踪 `.omp/` 保留。
