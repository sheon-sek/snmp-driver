# M1d-2 (#41) net-snmp 系统测试报告

日期：2026-10-26。分支：`m1d/release`。模块：`1.1.0-beta4`（源码含本票修复）。框架与复现步骤见 `system-test/README.md`。

## 结论

完整套件 47 个用例，46 个通过。唯一的失败是测试脚本竞态（`v3:inform-replay-in-window` 读基线前没等原始报文入账），修正后连跑 3 次均通过。测试中发现 3 个互通缺陷，已在本分支修复并补了回归测试；另有 1 个部署层面的问题（Docker 下 MAC 每次启动都变）通过文档和环境配置处理。Docker 端口映射实测会改写源 IP，结论已回填到手册变更清单 §9.3。

## 环境

| 项目 | 值 |
|---|---|
| 主机 | Windows 11 + WSL2 Ubuntu 24.04（内核 6.18.33.2-microsoft-standard-WSL2） |
| Docker | 29.8.1，rootful，默认 userland-proxy |
| Gateway | igdev 0.3.1，Ignition 8.3.10，standard edition，hostname/MAC 已固定（见下文缺陷 3） |
| 发送方 | `snmp-systest` 容器（python:3.12-slim + Debian net-snmp 5.9），与 Gateway 处在同一 igdev Docker 网络，直接发往 `gateway:10162` |
| 断言 | OPC UA（asyncua，Basic256Sha256/SignAndEncrypt）读取 `[Device]/Traps/*`、`Diagnostics/*`，订阅 SNMP Item 触发轮询 |
| License | 本机没有可用的签发 License，使用另一 session 做的 developer-mode bypass 构建（JVM 属性 `-Dsnmpdriver.developerMode=true`，补丁不入库）。被测 Trap 代码与入库源码一致 |

## 矩阵结果

| 验收项 | 用例 | 结果 |
|---|---|---|
| v2c Trap | `v2c:trap` | 通过。`Last/Json` 的 pduType/version/trapOid/source 正确，Sequence +1 |
| v1 enterpriseSpecific 转 v2 | `v1:enterprise-specific`、`v1:generic-linkdown` | 通过。trapOID 为 `enterprise.0.specific`（RFC 3584 §3.1），generic 2 → linkDown，agentAddress 保留 |
| v2c Inform：入队后 ACK、重复不计数 | `v2c:inform`、`v2c:inform-duplicate`、`v2c:inform-not-accepted` | 通过。同一 PDU 从新源端口重放 3 次：3 次都 ACK，Accepted 1、Duplicate 2（去重键包含源 ip:port）。`trapAcceptInform=false` 时不 ACK、不接受 |
| v3 安全等级 × 算法 | `v3:<level>:<auth>:<priv>`，共 17 个组合 | 全部通过。noAuthNoPriv；authNoPriv × MD5/SHA1/SHA256/SHA512；authPriv × 上述 4 种 auth × DES/AES128/AES256。每个组合都测了 Trap 与 Inform |
| v3 Inform discovery engineID = `Traps/LocalEngineId` | `v3:inform-engineid-matches-local` | 通过（在 discovery Report 报文中核对） |
| community/口令/engineID 错误 → `AuthFailureCount` + `LastError` 提示 | `v2c:wrong-community`、`v2c:trap-community-field`、`v3:wrong-auth-pass`、`v3:wrong-priv-pass`、`v3:inform-wrong-priv-pass`、`v3:wrong-engineid`、`v3:wrong-user` | 通过（privacy 口令错误一项是修复缺陷 1 之后才通过） |
| Gateway 重启后 v3 Inform 可用、boots 递增 | `restart:gateway-v3-inform` | 通过：engineID 不变，boots 加 1；持有旧 boots 的报文被按防重放拒绝；重新 discovery 后正常 ACK（需先修复缺陷 2 并固定 MAC） |
| 发送方重启 | `v3:sender-restart-boots` | 通过：boots+1 且 time 归零的 Trap 被接受，boots 回退的被拒绝（notInTimeWindow） |
| 窗口内重复包、超窗旧包，不清缓存 | `v3:inform-replay-in-window`、`v3:inform-replay-stale` | 通过：窗口内的重放 ACK 并计为 Duplicate；160 s 后的重放按 notInTimeWindow 拒绝，之后新的 Inform 照常可用 |
| 端口被占用 → 只有 Trap Degraded、轮询正常；释放后 30 s 内恢复 | `port:occupied-degraded-recovers` | 通过。占用期间 sysName 轮询正常；释放后 22–23 s 回到 Listening |
| 两个 Device 共享 listener | `route:multi-match-broadcast` | 通过：ListenerEndpoint 相同，两台都收到，MultiMatch 计数 |
| 同端口不同 bindAddress | `listener:bind-conflict` | 按 ADR 0007 通过：**保存不报错**，后启动的设备进入 Degraded，`LastError` 写明冲突设备和地址，先启动的设备不受影响。#41 验收文字“保存报错”已被 ADR 0007 取代 |
| Device 禁用后再启用；Trap 关闭 | `device:disable-enable`、`device:trap-disabled` | 通过 |
| 模块重载后恢复 | `module:reinstall` | 通过（`igdev module install` 后会重启 Gateway） |
| License 失效时丢弃，恢复后自动接收 | 手动 | **部分验证**：缺少 License 时 `Status=Unlicensed`，Trap 计入 `DroppedUnlicensedCount`，`LastError` 有提示（实测 Received 1 / DroppedUnlicensed 1 / Accepted 0）。“运行中恢复”需要上传签发的 License，本机没有签名私钥，未实测；由 `SNMPDeviceTrapNodesTest.lastNodesAreBadWithoutLicenseAndRecoverWithoutRestart` 和 `TrapLicenseWatchTest` 覆盖。建议在有 License 的环境补测 |
| 未配置来源只计 Unmatched；多 Device 匹配时广播 | `route:unmatched`、`route:allowlist`、`route:multi-match-broadcast` | 通过 |
| 轮询不受 Trap 影响 | `poll:unaffected-by-traps` | 通过 |
| Docker `-p 162:10162/udp` 源 IP 实测 | `docker:udp162-mapping-source-ip` | 已实测：**源 IP 不保留**，见下文 |

## Docker 端口映射（Q31）

用例运行时临时给 compose 加 `162:10162/udp` 并重建 Gateway 容器，结束后撤回。

| 发送方 | 目的 | Gateway 看到的源地址 |
|---|---|---|
| 默认 bridge 网络上的容器 172.17.0.2 | 172.17.0.1:162 | 172.18.0.1:57768 |
| WSL 主机本机 | 127.0.0.1:162 | 172.18.0.1:58440（Inform ACK 能回到主机） |

两条路径的源地址都被改写为 Gateway 所在网络的网关地址，按源 IP 归属 Device 的逻辑因此失效。真实局域网外部流量在 WSL2 NAT 下无法测试。手册结论：Linux 优先使用 host 网络加 nftables；必须用 `-p` 时，上线前用 `Traps/Last/Source` 核对源地址。详见 `docs/USER_MANUAL_1.1.0_CHANGES.md` §9.3。

## 发现的缺陷与修复

1. **privacy 口令错误的 v3 报文被静默丢弃，不计数。** DES/AES 用错误的 key 解密不会报错，只会产生乱码；USM 摘要校验通过后，MPv3 解析 scopedPDU 失败并返回 `SNMP_MP_PARSE_ERROR (-1408)`，而 SNMP4J 3.12.2 `MessageDispatcherImpl` 只对固定的一组状态触发 `AuthenticationFailureEvent`，其中没有它（也没有 `USM_DECRYPTION_ERROR`、`USM_PARSE_ERROR`）。修复：`TrapUsmStack` 的 MPv3 子类在“USM 已通过、MPv3 解析失败、authPriv”时，以对端地址直接上报；USM 层的 1406/1409 也走同一个 `SilentRejectionListener`。`LastError` 提示检查 Privacy Protocol/Passphrase。第一版用 `TransportStateReference.getAddress()` 作为对端地址，单元测试能过（监听地址与发送方都是 127.0.0.1），在 Gateway 上却不计数；改用 `prepareDataElements` 的 `address` 参数。回归测试：`TrapV3ReceiveTest.wrongPrivPassphraseCountsAuthFailure`、`netSnmpTrapWithWrongPrivPassphraseCountsAuthFailure`（4 份 net-snmp 实抓报文）、`TrapV3InformTest.wrongPrivPassphraseTrapOnAnInformEnabledStackCountsAuthFailure`。
2. **Gateway 启动后第一台 Device 的 `Traps/LocalEngineId` 一直为空，克隆提示也不显示。** `SNMPDevice.onStartup` 在 `services.ensureStarted()`（Trap registry 在这里创建）之前就执行了 `createAddressSpace()` 并读取 engine identity。修复：显示 identity 的步骤移到 `ensureStarted()` 之后。回归测试：`SNMPDeviceTrapNodesTest.firstDeviceAfterGatewayStartShowsTheLocalEngineId`（夹具新增“服务未预启动”模式，与 Gateway 冷启动的顺序一致；原夹具先启动服务，掩盖了这个问题）。
3. **Docker 下每次 Gateway 重启都被判定为克隆**（部署问题，代码不改）。容器内只采集到 `macAddress`，而 Docker 29 给未指定 MAC 的容器每次启动都分配随机 MAC（包括 `docker restart`）。结果：engineID 每次重启都重新生成，boots 永远是 1；同一组标识的 Module License 也会失配。处理：手册 §6 改为“Docker 必须固定 MAC”，ADR 0009 增加补记，`st.py setup` 为测试 Gateway 固定 hostname/MAC。固定后实测 boots 1→2→…→10，engineID 不变。

## 观察（未改代码）

- 端口被占用时，`LastError` 的处置建议先讲低端口权限，“端口被其他进程占用”放在最后一句。信息是全的，可以考虑按 errno 区分措辞，留给后续。
- 去重键包含源端口：发送方换源端口重发同一个 Inform，会被当作新报文。这符合 ADR 0004 的设计，手册无需另写。

## 产物

- `system-test/`：Dockerfile、`opc.py`、`st.py`、`cases.py`、`snmpd.conf`、README。不含 credentials、证书、Gateway 导出。
- 每次运行的原始结果在 `system-test/out/results-*.json`（git 忽略）。
