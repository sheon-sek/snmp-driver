# M0 IPv6 本地不可用分类修复

日期：2026-10-22。工作树 `/home/sheon/orca/workspaces/snmp-driver/m0-integration`，分支 `m0/integration`，修复基线 `9ad7f23d82d863c74a953682b4fe0531a5bc3967`。

只处理 final-spec-review 的 P2，依据 `docs/SNMP_DRIVER_V2_PLAN.md` M0-3：本地 IPv6 transport 缺失属于 `transport-unavailable`，质量为 `Bad_NoCommunication`，三个分类标志均为 false。此前 GET/SET 共用的 DNS 路径抛普通 IOException，被当作网络失败，持续本地失败可将网络退避推进到 60 秒。

## 实现与边界

- 新增轻量的 `SnmpTransportUnavailableException extends IOException`。`DnsResolver.resolveAddress` 在 IPv6 mapping 不可用且 Device 要求 IPv6，或 AUTO 只有 IPv6 地址时抛出它。GET/SET 继续使用原共用路径，保留现有原因文案和 IPv4 优选。
- `SnmpFailureClassifier` 在普通 IOException 之前识别新异常，输出 `transport-unavailable / Bad_NoCommunication / circuitFailure=false / legalResponse=false / unexpected=false`。真实网络 I/O 失败分类不变。
- `DeviceStatusMachine` 显式将新分类映射为 Degraded，有效响应仍可恢复 Running。否则仅修改分类会让设备保留原状态。
- 复用既有 transport factory、DeviceStatusMachine、fake client 与可控 scheduler 测试边界，未增加生产注入点。协调器、熔断器无需改动；新分类进入既有非网络探测退出路径。

## Red 与 green 证据

所有命令从上述 worktree repo root 执行，以 `bash /home/sheon/orca/workspaces/snmp-driver/m0-integration/gradlew` 为入口。

| 阶段 | 参数与实际结果 |
| --- | --- |
| 实际 GET/SET 路径 red | `:gateway:test --tests '*Snmp4jRequestClientTest.ipv6OnlyDeviceReportsUnavailableTransportForGetAndSet' -x :web-ui:viteBuild`；IPV6/AUTO × GET/SET 共 4 项失败，预期 transport-unavailable，实际 transport；不是编译失败 |
| 分类 green | 加入新异常、DNS 抛出和分类分支后，同命令 4 项通过；断言质量码、文案与三个标志 |
| 状态 red | `:gateway:test --tests '*DeviceStatusMachineTest.localTransportAbsenceDegradesAndValidResponseRecovers' -x :web-ui:viteBuild`；1 项失败，预期 Degraded，实际 Running |
| 状态 green | 显式 Degraded 映射后，同命令 1 项通过，包含 Running→Degraded→Running |
| 探测与退避回归 | `:gateway:test --tests '*HalfOpenRecoveryTest' -x :web-ui:viteBuild`；32 项通过，包含新增的 GET/SET 连续本地失败不打开熔断、同步/异步包装异常后探测恢复，以及连续本地暂停后下一次网络退避仍为 4 秒加抖动 |
| 最终全量 | `:gateway:test :base:test -x :web-ui:viteBuild`；BUILD SUCCESSFUL in 12s，9 actionable tasks，1 executed、8 up-to-date；gateway:test 实际执行，base:test 为 UP-TO-DATE |

最终 XML 读取自 `gateway/build/test-results/test/TEST-*.xml` 和 `base/build/test-results/test/TEST-*.xml`，按 tests/failures/errors/skipped 属性求和。

| 模块/测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| gateway / DeviceDiagnosticsTest | 1 | 0 | 0 | 0 |
| gateway / DeviceStatusMachineTest | 14 | 0 | 0 | 0 |
| gateway / CallbackDispatchTest | 8 | 0 | 0 | 0 |
| gateway / DeviceCircuitBreakerTest | 1 | 0 | 0 | 0 |
| gateway / HalfOpenRecoveryTest | 32 | 0 | 0 | 0 |
| gateway / SharedPollCoordinatorTest | 16 | 0 | 0 | 0 |
| gateway / Snmp4jKeyCacheTest | 12 | 0 | 0 | 0 |
| gateway / Snmp4jRequestClientTest | 11 | 0 | 0 | 0 |
| gateway / Snmp4jSecurityIsolationTest | 6 | 0 | 0 | 0 |
| gateway / SnmpValueCodecTest | 1 | 0 | 0 | 0 |
| gateway / SubscriptionBridgeTimestampTest | 1 | 0 | 0 | 0 |
| gateway 实际执行合计 | 103 | 0 | 0 | 0 |
| base 既有 XML，未重新执行 | 10 | 0 | 0 | 0 |

gateway XML 时间为 2026-10-22T03:46:06–03:46:17 UTC，base 为 2026-10-19T08:12:43 UTC。原 gateway 为 93 项；本次参数化增加 2 项、状态增加 1 项、探测及退避增加 7 项，共 103 项。base 的 10 项不能表述为本轮实际执行。

## 限制与交付

仅运行本任务授权的定向和 gateway/base 测试。未运行完整 build、UI 构建、Gateway 变动、系统 IPv6 禁用或厂商互通验收。Gradle 仍提示现有 deprecated API、Java 探测路径与未来 Gradle 9 兼容问题，未导致失败。

当前 Gateway 已安装 artifact 与 `.scratch/igdev-e2e-positive-report.md` 的真实 E2E 均对应旧提交 `9ad7f23d82d863c74a953682b4fe0531a5bc3967`，不能作为本修复提交已部署或已完成新 E2E 的证据。实际主机禁用 IPv6、长期压力、AUTH_NO_PRIV、真实 engineID 更换等覆盖边界保留。本地错误现在不会推进网络退避，实际恢复 transport 仍依赖原有客户端重建流程。

历史检索在开始及提交前执行，Hindsight 均返回 `ECONNREFUSED 127.0.0.1:8888`，没有可用历史知识页；依据当前源码、v2 规格及已完成的评审，不据不可用记忆作结论。仅提交修复源码、必要回归和本文；不包含既有 `.omp/` 或用户文件，不合并 PR、不关闭 Issue、不改旧部署。
