# M0-S 契约调查记录

日期：2026-10-19。对应 [Issue #2](https://github.com/sheon-sek/snmp-driver/issues/2)，仓库基线 `7e8e793`，分支 `research/m0s-contract`。

本记录覆盖 v2 方案 §5.3 的全部十项，只提供调查结论与方案修改建议。未修改生产代码、配置或 v2 方案，未运行构建、测试、基准、igdev 或 Docker。`已确认` 表示源码或官方文档支持，不能等同于 Gateway 实测通过；`否定` 指指定假设不成立；无法静态证明的行为标为 `仍需实测`。

## 证据范围与复现入口

仓库路径缩写 `S/` 为 `gateway/src/main/java/com/jw/snmpdriver/`。外部源码缩写如下，行号均按对应版本源码，而非 master 分支：

| 缩写 | 版本与获取位置 | 本次只读位置 |
|---|---|---|
| `J/` | [SNMP4J 3.12.2 sources](https://repo1.maven.org/maven2/org/snmp4j/snmp4j/3.12.2/snmp4j-3.12.2-sources.jar) 内 `org/snmp4j/` | `/tmp/m0s-research/src/snmp4j-3.12.2-sources/org/snmp4j/` |
| `M/` | [Milo sdk-server 1.0.5 sources](https://repo1.maven.org/maven2/org/eclipse/milo/milo-sdk-server/1.0.5/milo-sdk-server-1.0.5-sources.jar) 内 `org/eclipse/milo/opcua/sdk/server/` | `/tmp/m0s-research/src/milo-sdk-server-1.0.5-sources/` |
| `M14/` | [Milo sdk-server 1.1.4 sources](https://repo1.maven.org/maven2/org/eclipse/milo/milo-sdk-server/1.1.4/milo-sdk-server-1.1.4-sources.jar)，同上包路径 | `/tmp/m0s-research/src/milo-sdk-server-1.1.4-sources/` |
| `MC/` | [Milo stack-core 1.0.5 sources](https://repo1.maven.org/maven2/org/eclipse/milo/milo-stack-core/1.0.5/milo-stack-core-1.0.5-sources.jar) 内 `org/eclipse/milo/opcua/stack/core/` | `/tmp/m0s-research/src/milo-stack-core-1.0.5-sources/` |
| `G/` | [Gson 2.10.1 sources](https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1-sources.jar) 内 `com/google/gson/` | `/tmp/m0s-research/src/gson-2.10.1-sources/` |

SDK 使用 `gateway-api 8.3.0` 缓存 jar 与 `opc-ua-gateway-api 10.3.0` 的 jar/javadoc。没有取得后者的 sources jar，不把反汇编结果冒充源码行号。下文 SDK 字节码证据以类、方法、指令偏移定位，使用 `javap -p -c` 阅读，未执行其中方法。SDK API 同时对照官方 8.3.0 javadoc。

Milo 1.0.5 是 10.3.0 API POM 的依赖版本；先前会话发现本机已安装 OPC UA 10.3.8 携带 Milo 1.1.4。本次同时阅读两个版本的采样实现，不能把本机安装版本等同于 igdev 目标环境或所有 8.3 patch 版本。

Context7 已先解析 library ID，再检索 docs。Ignition ID 为 `/websites/inductiveautomation_8_3` 和 `/websites/sdk_inductiveautomation_javadoc_ignition83_8_3_6`，Milo 为 `/eclipse-milo/milo`。SNMP4J 返回 `/brettwooldridge/snmp4j`，该索引不是固定 3.12.2 版本，故仅作导航，结论以 Maven 3.12.2 源码为准。Milo 检索主要返回客户端示例，未直接回答服务端问题。未遇到配额错误。项目与个人 approved-knowledge 检索均无相关结果；历史会话只用于定位材料，技术结论重新核对当前文件。

## 1. Milo 动态项采样与质量码，R6

结论：额外调用 `read()` 已确认；“因此重复发送 SNMP GET”否定；“必然把 Bad 覆盖为 Good”否定。双写时序仍需实测。

- `M/util/SubscriptionModel.java:126–145` 按采样间隔分组并调度；`:168–205` 按 session 调用 `addressSpace.read(context, 0d, Both, ids)`，随后逐项 `item.setValue(value)`。不区分真实节点与动态项。1.0.5 在执行完后再等待完整间隔，1.1.4 的 `M14/util/SubscriptionModel.java:148–150,172–216` 扣除执行耗时后安排下一次，不能假设两个版本采样周期完全一致。
- 当前 `S/gateway/device/SNMPDevice.java:113–124,300–303` 把动态项也交给 SubscriptionModel，同时交给 SubscriptionBridge。`S/gateway/device/DynamicAttributeReader.java:134–135` 读取 `subscriptions.latest(nodeId)`，无缓存时返回 `Bad_WaitingForInitialData`，不执行 GET。
- `S/gateway/device/SubscriptionBridge.java:232–240` 先更新 latest 缓存，再直接 `item.setValue`。Milo 稍后采样读同一缓存，属于重复读取/发布通道。
- `MC/types/builtin/DataValue.java:117–135` 的 `derivedValue` 保留原 value、status 和被请求的 sourceTime，只按请求更新/去除时间戳。`M/items/MonitoredDataItem.java:75–85` 经过过滤才入队，不能据 `setValue` 调用次数推算客户端通知数。单项过滤并不消除两个线程先后发布旧值、新值的竞态。

方案影响：将 §2.3 R6 从笼统待验证改成“双重采样/推送已确认，无额外 GET”。§3 M0-8、§4.7 和 §7 应明确动态项的发布所有权；后续决定是否只让真实节点进入 SubscriptionModel，必须同时处理 create/modify/delete/restore/mode-change。保留质量、时间戳和竞态的 Gateway 检查，不能直接宣称用户已观察到回退。

## 2. 模块目录 API、备份与恢复

结论：API 与备份契约已确认；实际恢复、克隆和写入失败行为仍需实测。

入口为 `gatewayContext.getSystemManager()`。建议共享引擎文件使用 `getModuleConfigLocalDir("com.jw.snmpdriver").resolve("trap-engine.properties")`，得到 `$data/config/local/com.jw.snmpdriver/trap-engine.properties`。该身份属于机器，不应放到某个 Device 目录。SDK 返回 Path，调用方负责创建目录。

证据为 [SystemManager 8.3.0 javadoc](https://sdk.inductiveautomation.com/javadoc/ignition83/8.3.0/com/inductiveautomation/ignition/gateway/system/SystemManager.html#getModuleConfigLocalDir(java.lang.String))，并以 8.3.0 jar 的同名 default 方法字节码核对路径解析：

| API | 目录 | `.gwbk` 契约 |
|---|---|---|
| `getModuleConfigDir(id)` | `$data/config/{id}` | 包含，适合共享配置 |
| `getModuleConfigLocalDir(id)` | `$data/config/local/{id}` | 包含；恢复时可选择是否恢复 local 内容 |
| `getModuleTransientDir(id)` | `$data/var/{id}` | 不包含，不适合需要备份的引擎身份 |

Context7 返回的 [Gateway folder structure](https://docs.inductiveautomation.com/docs/8.3/appendix/reference-pages/gateway-folder-structure) 与此相符。不要混淆旧 `getLocalDir()`、local resource collection 与模块 config/local 路径。API 存在不证明目标文件系统支持 `ATOMIC_MOVE`。

方案影响：§4.6 固定具体 API，避免重复拼接 module ID。恢复 local 会恢复旧 engineID/boots，克隆可能复制身份，旧备份还可能使 boots 回退；只“读取旧值加一”不能证明恢复后一定大于远端记录。保留原子写失败停用 Inform，并补充灾备恢复、复制部署时引擎身份处理流程。§6.3 增加恢复 local 与不恢复 local 两种情况。

## 3. validate 与跨设备配置，含禁用设备

结论：`validate` 不直接提供全体设备或当前资源身份，已确认；以现有 ServiceBundle 注册表作为完整配置来源，否定。完整保存期校验机制仍需 Gateway 核实。

证据：`S/gateway/device/SNMPDeviceExtensionPoint.java:28–30,75–117` 仅注入 ServiceBundle；[AbstractExtensionPoint 8.3.0](https://sdk.inductiveautomation.com/javadoc/ignition83/8.3.0/com/inductiveautomation/ignition/gateway/config/AbstractExtensionPoint.html) 的签名只有 `validate(settings, errors)`。GatewayContext 可以由模块显式提供，但这不是方法参数自带的能力。

`S/gateway/service/ServiceBundle.java:26,158–164` 存的是运行时 `Device` 对象；扩展点在 `createDevice` 后注册，见 `SNMPDeviceExtensionPoint.java:61–65`，既非资源快照，也没有列举所有配置的接口。不能证明它包含禁用、创建失败或尚未实例化的 Device。

8.3.0 jar 中 `GatewayContext#getConfigurationManager`、`ConfigurationManager#getResources(ResourceType)` 可作为读取配置资源的入口；`DecodedResource#enabled` 可访问启用状态。但 `validate` 缺少当前资源名/路径，直接扫描容易把编辑前的自己当成冲突；遍历时再次走同一 codec 解码，还可能递归触发校验。`ExtensionPointResourceCodec#decodeFromJson` 字节码偏移 166 调用 extension point decode，182 调用 validateSettings，202 在错误时构造 ValidationException。它不是仅点击保存时才运行的 hook。

方案影响：§4.8 删除“直接使用已注册 Device 配置即可完整保存校验”的假设。单设备纯校验留在 validate；跨设备校验需要可识别当前资源、覆盖禁用资源且避免递归的配置快照/资源层入口。批量导入和并发保存还需运行期 `registry.acquire` 复查。未经实测，不承诺现有方法能满足 D4 的完整保存期拒绝条件。

## 4. 多节点 setValue 的原子性与通知顺序

结论：当前 API 路径上的跨节点原子更新假设否定；客户端最终通知顺序仍需实测。

`opc-ua-gateway-api 10.3.0` 的 `DeviceContext` javadoc 与 jar 方法列表没有批量原子写 API。`M/nodes/UaVariableNode.java:271–272,338–381` 将 Value 写入当前节点，单节点 synchronized，不是跨节点事务；`M/nodes/UaNode.java:657–662` 对当前节点逐个调用 observer。`M/ManagedAddressSpace.java:128–147` 的批量 read 也是逐项访问。相同 sourceTime 只是数据属性，不能提供原子性或跨节点通知排序保证。

§4.7 的“先读 Sequence，读字段，复核 Sequence 未变”也不充分：若 writer 先改字段再改 Sequence，读者可能在 Sequence 更新前两次都读到旧序号，却读到部分新字段。把 Sequence 放在最前面也有对应窗口。

方案影响：§4.7 明确 Last/* 是非原子镜像。若需要一致读取，可让一个 Read 请求对不可变 Snapshot 只取一次引用，或提供带 Sequence 的单值快照；跨多次请求仍不能保证同一快照。若使用 seqlock，必须有写入中标识及开始/结束版本，读者拒绝写入中状态，不能继续沿用“每条只加一”的原算法却声称一致。此处仅建议，不在本调查改变 Q2 的 Snapshot 语义或节点契约。

## 5. SNMP4J 3.12.2 子项

### 5.1 隔离 MPv3 后的全局读取

结论：主安全模型可隔离，已确认；“五参 MPv3 使整个库完全不接触全局单例”否定。

`J/mp/MPv3.java:169–185` 接收独立 protocols、models、counterSupport；`J/security/USM.java:220` 用注入的 protocols 创建 UsmSecurityParameters。`J/DirectUserTarget.java:340–369` 的静态 `SecurityProtocols.passwordToKey` 使用传入协议对象，不能仅因 static 就判为全局注册表污染。

但 `J/security/UsmSecurityParameters.java:215–230` 的 `getBERMaxLength` 直接读取全局 SecurityProtocols 的最大 auth/priv 长度，`J/security/USM.java:894–895` 用它计算可响应 scoped PDU 大小。`J/mp/MPv1.java:195` 和 `mp/MPv2c.java:198` 建 StateReference 时读取全局 SecurityModels。`J/Snmp.java:312–322`、`USM.java:85–86` 仍可取得全局 CounterSupport；v2 方案本身也明确使用全局计数器。

方案影响：§3 M0-6 和 §4.6 保留“不向全局 SecurityModels 注册本模块 USM”的验收，把“完全隔离”改成限定范围。必要时显式传入独立 CounterSupport；残留的全局尺寸估算应通过实际 v3 请求/响应检查，不能用只检查注册表的测试证明完全隔离，也不应为修复残留读取而重新污染全局表。

### 5.2 并发请求与 engine 缓存

结论：单次 map 操作同步已确认；discovery 单次执行、缓存驱逐原子性和换 engine 自动恢复仍需实测。

`J/mp/MPv3.java:1817–1822` 默认缓存是 synchronizedMap；`:444–454` 的 size/contains/put 是复合操作，容量检查并非一个共同临界区。`J/MessageDispatcherImpl.java:684–696` 在发送时查询/写入地址到 engineID 的缓存并更新 UserTarget。`J/DirectUserTarget.java:325–338` 同步保护的是单个 target，不能协调多个新 target 的发现。

`J/Snmp.java:1185–1188` 的显式 discovery 会移除该地址缓存，`:1398–1448` 的 ReportProcessor 可能在把错误交给业务层前自动重发 unknownEngineIDs/notInTimeWindows REPORT。不能假设所有 REPORT 都会变为 `SnmpSecurityReportException`，从而触发 §3 M0-7 的清理。

方案影响：M0-7 按 endpoint generation 隔离密钥，并保持每请求独立 target；新增同址并发 discovery、设备更换 engineID、自动 REPORT 重试后缓存是否一致的验收。清理 engine 缓存时考虑同址在途请求；不得宣称 map 同步即代表完整发现流程原子。

### 5.3 UsmTimeTable、重启与重放

结论：源码时间窗规则已确认；“USM 会拒绝所有重复 Trap”否定；厂商互通仍需实测。

`J/security/UsmTimeTable.java:145–199` 区分本地权威方和远端权威方。远端 engineBoots 增大时更新缓存；boots 小于缓存值拒绝；相同 boots 时比最新接收时间落后超过 150 秒拒绝。相同 boots、相同时间的重复包可以通过，时间窗不是逐报文去重表。本地权威方要求 boots 相同且本地时间差在窗口内，boots 达 2147483647 拒绝。`:128–129` 可显式删除远端时间项，`:202–203` 的 reset 方法为空，不能当清空缓存使用。

`J/security/USM.java:818` 在认证路径调用 checkTime；不应把认证时间窗保障套用到 noAuthNoPriv。新进程、未知 engine 的首次同步与已经建立时间项后的回退不是同一情况。

方案影响：§4.6 写明发送方正常 boots 递增可接受，同一 engineID boots 回退可能持续被拒，需诊断与明确恢复操作；不得自动反复清缓存绕过重放防护。§6.2/§6.3 分别覆盖 Trap 的远端权威方、Inform 的本地权威方，以及窗口内重复、超窗旧包、boots 回退。Inform 去重仍由 §4.5 负责。

### 5.4 本地化用户与 LocalizationGrant.never

结论：API 已确认，可实现 ADR 0003。

`J/security/UsmUser.java:199–219` 的六参构造最后一个参数是 localizationEngineID；前面的 auth/priv 字段必须已经是本地化密钥，不能把普通口令塞进去冒充密钥。`:480–495` 提供 `getLocalizationGrant()` 与链式 `setLocalizationGrant(LocalizationGrant.never)`；已本地化用户总是返回 never。

推荐使用 `USM.addUser(user, senderEngineId)` 在注册时进行固定 engineID 本地化。证据 `J/security/USM.java:1018–1044`；旧的三参 `addUser(securityName, engineID, user)` 在 `:992–996` 已标 deprecated 并转调新方法。也可预派生密钥后创建明确绑定 engineID 的 localized user，再 `addUser(user)`。不能对一个没有本地化的通配用户仅设置 never，就期待它自动生成密钥。

方案影响：§4.6 把 deprecated 示例换成上述固定 engineID 注册流程，v3 Inform 绑定 Gateway 本地 engineID，Trap 绑定发送方 engineID。每个 engineID/securityName 只注册所需项，不同时保留同名通配口令用户。

### 5.5 解码 OID 能否被 NumericOid 拒绝，R3

结论：对当前 3.12.2 默认解码和 `toDottedString` 路径，“越界 OID 触发 NumericOid.parse IAE”否定，属于静态推导，未发送畸形包复现。

`J/asn1/BER.java:818–902` 使用 int 累积每个子标识符，并重建首两个 arc；首 arc 仅为 0、1、2，首 arc 为 0/1 时第二 arc 在 0–39。即使输入发生 int 溢出，`J/util/SimpleOIDTextFormat.java:52–60` 也以 `& 0xFFFFFFFFL` 格式化每个 int。零长度 OID 被解码成 0.0。解码器对过长/非规范编码的宽容不能推导出格式化字符串越界，某些非法输入反而会被截断成另一个合法范围值。

`J/smi/OID.java:259–260,289–296` 的 `toDottedString` 使用固定数字格式化，与可变的全局 `OIDTextFormat` 无关。本仓库 `S/snmp/runtime/Snmp4jRequestClient.java:642–649` 正是用此方法，`S/snmp/address/NumericOid.java:19–37` 的边界能接纳上述输出。解码阶段的 EOF、错误类型等可以抛 IOException，但不是方案所指的 parse IAE 链路。

方案影响：修正 §2.3 R3 与 §3 M0-4，保留一般运行期 Errored 可恢复的修复，撤回“已确认可由远端越界 OID 触发”的说法。若保留 parse 防御转换，应标为内部不变量保护，不能把手造 OID 对象的测试冒充 UDP 远端复现。

## 6. record 新字段与旧配置迁移，Q12

结论：自动套用业务默认值的假设否定；显式迁移入口已确认；1.0.0→1.1.0 升级仍需 Gateway 实测。

当前 `S/gateway/device/SNMPDeviceConfig.java:12–50,59–65` 的注解和 defaults 方法定义表单/新建默认值。`SNMPDeviceExtensionPoint.java:34–35` 在 defaultSettings 返回 defaults，`:61` 也仅在整个 config 为 null 时替换，均不能补齐一个已经解码出的 record。

8.3.0 `AbstractExtensionPoint#decode` 字节码偏移 16–22 调用 ia-gson.fromJson。缓存 `ia-gson 2.10.1` 的 `ReflectiveTypeAdapterFactory$RecordAdapter` 字节码显示 PRIMITIVE_DEFAULTS；上游 `G/internal/bind/ReflectiveTypeAdapterFactory.java:442–493,512–515` 对应缺字段为 0/false/null，再调用 canonical constructor，并不调用 SNMPDeviceConfig.defaults。由此缺 trapEnabled 得 false，但 trapAcceptInform 也得 false，trapPort 得 0、trapSecurity 得 null，均不满足 §4.8 的完整默认值契约。

可在扩展点 `decode(JsonElement)` 中复制 JSON，仅为缺失的新增 trap* 字段补默认值，再委托基类解码；也可通过 `customizeGson` 注册合适的 adapter。证据为 [AbstractExtensionPoint javadoc](https://sdk.inductiveautomation.com/javadoc/ignition83/8.3.0/com/inductiveautomation/ignition/gateway/config/AbstractExtensionPoint.html)。必须区分“缺失”与用户显式提供的非法值，保留原有字段及 SecretConfig adapter，不要以全局 defaults 覆盖旧轮询配置。

方案影响：§4.8 与 M1a 明确先迁移/补齐再 validate。禁用 Trap 的旧配置也必须可解码，且首次启用时字段具备预期默认值。保留 §5.1/§6.3 的 Q12 硬性验收，静态阅读不足以关闭升级测试。

## 7. Docker UDP 端口映射与源 IP

结论：不能保证 `-p 162:10162/udp` 在所有部署保留源 IP，已确认；目标部署仍需实测。

实际经过用户态 UDP 代理时，[Moby UDP proxy](https://github.com/moby/moby/blob/master/cmd/docker-proxy/udp_proxy_linux.go) 用 `net.DialUDP("udp", nil, backendAddr)` 建立后端 socket，按源码推导不会把原发送地址用作后端 socket 源地址。但设置 userland-proxy=true 不表示每个包都经代理，Linux bridge 的内核转发、loopback 和跨地址族路径需分别看待。

[RootlessKit port driver 文档](https://github.com/rootless-containers/rootlesskit/blob/master/docs/port.md) 明确 builtin 的 v3.0 源 IP 传播能力仅限 TCP，UDP 不传播。不能从 [Docker rootless troubleshooting](https://docs.docker.com/engine/security/rootless/troubleshoot/) 的概括表推导 UDP 也已透明。其他驱动即便标示支持传播，也须在本项目 UDP 场景实测。宿主机发布 162 在 rootless 下还有低端口权限要求，容器内改为 10162 不会解除宿主机限制。端口语法见 [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/)。这些在线文档随版本变化，记录目标 Engine、RootlessKit、网络/端口驱动和代理设置后再验收。

方案影响：§4.3、§4.10、§5.2 和 §6.3 不能把 allowlist 描述为恢复原 IP 的方法。allowlist 只能接受代理地址；多个来源经同一代理后可能广播到多个 Device，并削弱按来源隔离。优先验证真正保留源地址的部署方式，host 网络也应限定目标平台并实测，不作跨平台通用承诺。

## 8. Windows SNMP Trap 服务

结论：服务名 `SNMPTRAP`、运行时使用 UDP 162 已确认；各目标 Windows 的安装状态、绑定地址与实际冲突仍需实测。

[Microsoft 服务与端口说明](https://learn.microsoft.com/en-us/troubleshoot/windows-server/networking/service-overview-and-network-port-requirements) 将 SNMP Trap 服务列为 `SNMPTRAP`，与 SNMP 服务的 UDP 161 区分。[Microsoft Monitor SNMP Trap](https://learn.microsoft.com/en-us/system-center/orchestrator/standard-activities/monitor-snmp-trap?view=sc-orch-2025) 明确 Trap Service 运行时使用端口 162，其他 receiver 使用该端口时需要禁用它。不能据此断言每台 Windows 都已安装/启动该服务。

方案影响：§4.10 将“通常为 SNMPTRAP”改为“服务名为 SNMPTRAP，先检查是否存在及是否运行”，再按运维授权处理。后续只读检查可用 `Get-Service -Name SNMPTRAP`，并用 [Get-NetUDPEndpoint](https://learn.microsoft.com/en-us/powershell/module/nettcpip/get-netudpendpoint?view=windowsserver2025-ps) 查 LocalPort 162 的 LocalAddress/OwningProcess，不能只凭显示名称判断占用。本次没有执行这些命令或停止任何服务。

## 9. v3 头部与 maxRequestBytes=1200，OMP-D

结论：不是固定头长，已确认；“160 B 一定不足”及“1200 默认必然频繁 tooBig”没有成立证据；实际包长仍需实测。

`S/snmp/runtime/SharedPollCoordinator.java:551–565` 从 160 B 起按每 OID `24 + 2×数字字符串长度` 估算，仅在批次非空时检查上限，因此单个超预算条目仍会发出。`Snmp4jRequestClient.java:379` 设置 target.maxSizeRequestPDU，但 `J/MessageDispatcherImpl.java:637–648` 传给 prepareOutgoingMessage 的是 transport 最大出站消息大小。普通发送路径没有以该 target getter 做完整报文硬限制，不能把 1200 当作抓包必然满足的字节上限。

证据：`J/mp/MPv3.java:751–760,918–961` 计算 HeaderData/scoped PDU；`J/security/UsmSecurityParameters.java:201–210` 合计 engineID、boots/time、userName、auth/priv 参数；`J/security/USM.java:590–630` 添加加密 OCTET STRING 包装与认证参数。SHA-256 的 auth code 为 24 B，SHA-512 为 48 B，见 `J/security/AuthHMAC192SHA256.java:36`、`AuthHMAC384SHA512.java:34`；这不是完整摘要长度。

精确预算应为：外层 BER SEQUENCE 与版本 + HeaderData + USM securityParameters OCTET STRING + 明文 scopedPDU 或加密 OCTET STRING。各个 TLV 使用实际长度及 length-of-length；engineID、securityName、contextName、整数编码宽度、OID、请求 ID 和算法都会影响结果。默认配置虽为 SHA256/AES128，但 securityName 和远端 engineID 没有固定默认长度，不能给出单一“默认 v3 开销”数值。

方案影响：§2.3 OMP-D、M0-9 与 §5.3 保留默认 1200，不据估算直接调参。后续记录不同安全级别、算法、短/长名字和实际 OID 的编码请求长、响应长与 tooBig 次数。响应 tooBig 由响应规模和设备能力决定，不能仅从 GET 请求长度推出。若未来要把配置定义为严格报文上限，需要额外实现，不是现状契约。

## 10. R8 修复前后 CPU 基准

结论：仍需实测，无前后基准数据。R8 尚未实现，不能报告加速百分比。

现状证据：`S/snmp/runtime/Snmp4jRequestClient.java:354–366` 每次构建新的口令型 DirectUserTarget；`J/DirectUserTarget.java:325–369` 获取 engineID 后执行本地化/派生。MPv3 的 engineID map 缓存不是应用的本地化密钥缓存，不能据其存在推翻 R8。

方案影响：M0-7 验收分开记录派生调用次数与 CPU/请求。未来固定 JDK、CPU、算法、设备/OID、并发度、预热、测量时长和请求完成数，分别测冷发现、热缓存、缓存失效和无认证对照；比较 CPU 时间/完成请求及延迟分位数，网络 RTT 不当作 CPU。提供原始数据与波动范围，不用单轮 wall-clock 推导性能。本项按方案不是发布门槛，未运行基准不应伪装成性能已验收。

## 需要回写 v2 方案的主要修正

| 章节 | 建议 |
|---|---|
| §2.3 R6、M0-8、§7 | 确认双通道采样，区分缓存 read、网络 GET、过滤后的通知；明确发布所有权 |
| §4.6 | 固定模块 config/local API；说明 local 恢复选择、克隆身份和 boots 回退 |
| §4.8、M1a | 不以运行时 Device 表代替配置集合；解决资源身份、禁用配置、递归解码与并发保存 |
| §4.7 | 撤回跨节点原子性及单纯 Sequence 双读的一致性保证，另选一致快照契约 |
| M0-6、§4.6 | 把安全模型隔离与残余全局尺寸/计数器读取分开验收 |
| M0-7 | REPORT 可能内部重试，失效不能只靠业务异常；补并发发现与换 engine 验收 |
| §4.6、§6.2 | 时间窗不是全部重放去重；使用固定 engineID 的现代用户注册 API |
| §2.3 R3、M0-4 | 撤回当前解码链能产生 NumericOid 越界字符串的假设，保留运行期恢复 |
| §4.8、§6.3 | 新字段在解码前显式补齐，仅缺失字段使用默认值；保留 Q12 升级验收 |
| §4.3、§4.10、§5.2 | 代理 IP allowlist 不能恢复设备区分；rootless UDP 不套用 TCP 透明结论 |
| §4.10 | 明确 SNMPTRAP 服务名，但目标系统实际占用先检查 |
| OMP-D、M0-9 | 1200 是当前批次估算预算，不是编码后硬上限；完整包长仍待记录 |

## 需要另行授权的实测清单，未执行

1. igdev Gateway：动态项重复采样的质量、source/server 时间戳、快速更新时序与实际 Milo 版本。
2. igdev Gateway：config/local 备份恢复两种选择、旧备份 boots 回退、克隆部署、原子写失败与进程重启。
3. igdev Gateway：禁用设备、编辑自身、并发保存、批量导入、启动解码时的跨设备校验。
4. igdev Gateway/OPC 客户端：Last/* 并发更新、不同订阅/Tag Group 的通知与快照一致性方案。
5. SNMP4J/net-snmp：实例隔离后的 v3 互通、残余全局尺寸估算、同址并发发现、REPORT 自动重试和 engineID 更换。
6. net-snmp：发送方 boots 正常递增/回退、窗口内重复、超窗旧包，以及 Inform 的 Gateway 权威时间窗。
7. igdev Gateway：完整 1.0.0 配置升级到 1.1.0，不改即可加载，Trap 默认关闭、轮询与 Secret 引用保持，首次启用默认字段正确。
8. Docker：真实远端 UDP 输入，记录 Engine/RootlessKit/driver/proxy 设置、Gateway transport peer IP 和 Inform ACK 返回路径，覆盖 rootful bridge、实际 proxy、rootless；localhost 发包不能替代远端验收。
9. 目标 Windows：SNMPTRAP 存在/运行状态、UDP 162 绑定地址/PID、Gateway 冲突诊断与释放后恢复。
10. 授权的编码/互通测量：不同 v3 参数的完整请求/响应长度，以及未来 R8 修复前后派生次数、CPU/请求与延迟。当前没有后版本可测。

上述项目是后续实现/系统验收输入。本次 M0-S 完成的是静态契约调查，不代表这些实测项目已经通过，也不代表 B1 可以发布。
