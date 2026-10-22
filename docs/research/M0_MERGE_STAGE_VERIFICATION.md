# M0 staging 合并验证

日期：2026-10-19。工作树为 `/home/sheon/orca/workspaces/snmp-driver/m0-7-key-cache`，分支为 `m0/merge-stage`。

## 合并结果

从本地 `m0/integration` 的 `c0ebb3bb0fcb080b9c23872f958d904b8b21df1d` 创建 staging，依次以 `--no-ff` 合入：

| 任务 | accepted source tip | staging merge |
| --- | --- | --- |
| #8 Device 状态恢复 | `b1f180cdf268ec2ffadc56c09470ce49acdb5091` | `9feffcf` |
| #9 有界回调 | `1b51138d661f7ad6f7f2af5dfb0a83b49dfdfbc9` | `430bbc1` |
| #10 安全模型隔离 | `67d9478aa37008b9989abe472063828e1abf6583` | `0d228a1` |
| #11 本地化密钥缓存 | `c41bc7ef978850534ae40f52e0ec202b0b6057f7` | `a35100b` |

协调者已更正 #11 tip：原指定的 `5d1167d3bc5ce27011b1c7e12f7e4af818a988a5` 只是合入 integration 的父 merge，后续 `c41bc7e` 才包含缓存实现及 11 项测试。

四次合并均自动完成，没有文本冲突，也没有手工生产代码修正。以 `git merge-base --is-ancestor` 确认上述四个 accepted tips、原 #11 merge `5d1167d` 和 integration 基线均为合并结果 `a35100bc04b18c90e8c40cf1c95fe25689b0e4ff` 的祖先。原有规格、M0-S 调查、各 source tip 的测试文件及验证文档全部保留。

## transport 不可用的状态核对

已读取 [父规格 #4](https://github.com/sheon-sek/snmp-driver/issues/4) 及 [#8](https://github.com/sheon-sek/snmp-driver/issues/8)、[#9](https://github.com/sheon-sek/snmp-driver/issues/9)、[#10](https://github.com/sheon-sek/snmp-driver/issues/10)、[#11](https://github.com/sheon-sek/snmp-driver/issues/11) 的正文、标签和评论，以及 authoritative AGENTS、domain、issue-tracker 和 source branch 文档。

当前源码已经满足 #8 报告提出的 IPv6 transport 不可用应显示 Degraded 的要求：

- `DnsResolver.java:91–99` 在 IPv6 transport 缺失且目标要求 IPv6 时抛出带明确原因的 `IOException`。
- `SnmpFailureClassifier.classify` 的 `IOException` 分支把异常分类为 `transport`，`circuitFailure=true`，`unexpected=false`。
- `DeviceStatusMachine.java:70–71` 对 circuit failure 发布 `Degraded`；`SNMPDevice.java:504–508` 的当前 generation 失败回调接入此状态机。
- 既有 `Snmp4jRequestClientTest.ipv6OnlyDeviceReportsUnavailableTransportForGetAndSet` 覆盖 IPV6/AUTO 的 GET、SET 失败分类；状态机既有测试覆盖 circuit failure 从运行期 Errored 转为 Degraded。

该行为在 #7 与 #8 合并后已存在，因此没有新增集成修正或测试，也没有引入新行为。

## 授权测试结果

从上述工作树根目录执行：

```sh
bash /home/sheon/orca/workspaces/snmp-driver/m0-7-key-cache/gradlew :gateway:test :base:test -x :web-ui:viteBuild --console=plain
```

结果为 `BUILD SUCCESSFUL in 17s`，9 个 actionable tasks 中 3 个执行、6 个 up-to-date。gateway 测试实际执行；base 测试任务为 `UP-TO-DATE`，下表如实汇总最终 `build/test-results/test/TEST-*.xml`，不声称 base 本轮重新执行。

| 模块 / 测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| gateway / DeviceStatusMachineTest | 13 | 0 | 0 | 0 |
| gateway / CallbackDispatchTest | 8 | 0 | 0 | 0 |
| gateway / DeviceCircuitBreakerTest | 1 | 0 | 0 | 0 |
| gateway / HalfOpenRecoveryTest | 25 | 0 | 0 | 0 |
| gateway / SharedPollCoordinatorTest | 15 | 0 | 0 | 0 |
| gateway / Snmp4jKeyCacheTest | 11 | 0 | 0 | 0 |
| gateway / Snmp4jRequestClientTest | 9 | 0 | 0 | 0 |
| gateway / Snmp4jSecurityIsolationTest | 6 | 0 | 0 | 0 |
| **gateway 合计** | **88** | **0** | **0** | **0** |
| base / LicenseAccessSnapshotTest | 2 | 0 | 0 | 0 |
| base / LicenseRequirementTest | 4 | 0 | 0 | 0 |
| base / AbstractRuntimeServiceTest | 4 | 0 | 0 | 0 |
| **base 合计** | **10** | **0** | **0** | **0** |

Gradle 输出包含 Java 安装路径探测、deprecated API 和 Gradle 弃用提示，未阻止测试。没有运行 Gateway、UI、完整 build 或系统检查。

## 保留与后续

未推进 `m0/integration`，未操作 canonical integration 工作树，未 push 或修改 Issue/PR。原 `sheon-sek/m0-7-key-cache` 保持 `c41bc7ef978850534ae40f52e0ec202b0b6057f7`。

预存 `.omp/mcp.json` 保持未跟踪，内容 SHA-256 在切换与合并前后均为 `97167bd6030f77f6b495f608e7d770c577c5eaf84a7f835d520a8614200840cf`；未暂存本地 policy/config 或 `.omp`。协调者后续另行派发 staging promotion，本任务只交付可审阅的 staging 分支。
