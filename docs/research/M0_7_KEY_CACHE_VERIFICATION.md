# M0-7 本地化密钥缓存验证

日期：2026-10-19。范围：[父规格 #4](https://github.com/sheon-sek/snmp-driver/issues/4) 与 [任务 #11](https://github.com/sheon-sek/snmp-driver/issues/11)。已通过 `gh issue view` 读取两者的正文、评论和标签。

## 基线与实现

- 工作树基于已验收的 #10 提交 `67d9478`，包含 #7 IPv6 transport 清理。
- 验证前已合入当时最新的 `m0/integration`，集成分支提交为 `c0ebb3bb0fcb080b9c23872f958d904b8b21df1d`。
- `LocalizedKeyCache` 按 endpoint 对象身份、实际 authoritative engineID、authentication/privacy 协议 OID 隔离，最多保存 1024 项本地化密钥，使用 LRU。用户及凭据属于不可变 endpoint，同值的新 endpoint 也视为新 generation。
- 每次公开 GET/SET 创建独立 target。SNMP4J 为 pending request 复制 target 时，保留缓存与请求 generation 信息，但不共享可变密钥容器。
- 在每次编码时从实例 MPv3 选择实际 engineID 与匹配密钥。内部 REPORT 重试也走此路径，旧 target 不会把旧 engineID 写回地址缓存。
- generation 检查覆盖接收解码与 MPv3 的 engineID 写入，避免旧在途响应在业务回调之前污染引擎缓存。generation 元数据使用弱引用，密钥条目仍受 1024 上限约束。
- 当前 generation 的安全 REPORT 或超时会清理对应缓存并移除仍匹配失败请求的地址到 engineID 提示。旧 generation 的失败不会清理新配置；USM 时间表保持原有防重放行为。

实现见 `gateway/src/main/java/com/jw/snmpdriver/snmp/runtime/LocalizedKeyCache.java` 和 `Snmp4jRequestClient.java`。未修改配置、License、动态路径、发布所有权、最小轮询间隔或默认 `maxRequestBytes=1200`。

## TDD 与行为证据

测试入口为已批准的 transport factory 和 key-cache 局部边界，使用进程内 UDP SNMP4J Device。计数器继承真实 SHA-256 authentication protocol，其 `passwordToKey` 每次调用都执行原实现，不用缓存命中数替代派生计数。

| 场景 | 证据 |
| --- | --- |
| 连续 3 次 GET 与 1 次 SET | 修改前红灯为真实派生 8 次；修改后共 2 次，即 auth 与 privacy 各一次，4 个 secured request 使用 4 个独立 target |
| 同址 8 个并发 cold discovery | 先扣留全部 8 个 discovery REPORT，再释放；8 个请求均完成，8 个独立 secured target，共 2 次真实派生 |
| 换 engineID | 限定最多 3 次轮询恢复；两个实际 engine 共 4 次派生，随后热请求不增加派生 |
| authenticated notInTimeWindow REPORT | Device 观察到同一公开请求的内部重发，两次 secured request，共用原有 2 次派生结果 |
| 当前 generation 的 wrongDigest REPORT | 红灯为派生仍为 2 次；实现失效后下一次请求重新发现并派生，总计 4 次 |
| 旧 generation 的延迟 REPORT | 在解码阶段丢弃，旧请求最终超时；新 generation 保持原有热密钥 |
| 旧 engine 的已编码延迟响应 | 旧请求 timeout 设置为 5000ms，确保新 generation 已恢复时仍在途；红灯为旧请求仍被成功接受；修复后拒绝旧响应，MPv3 保持新 engine，下一次新请求不重新派生 |
| 没有业务 REPORT 的静默换引擎 | 扣留 unknownEngine REPORT，确认超时移除过期 engine 提示；放开 REPORT 后恢复并保持热缓存 |
| 精确容量与 LRU | 1024 个不同实际 engine 的条目均可复用；第 1025 项淘汰最久未使用项，刚使用项保留；重新访问被淘汰项才增加派生 |
| 用户与凭据隔离 | 两个用户名各自冷派生后可同时保持热缓存；错误凭据独立派生且无法借用已有用户的成功密钥 |
| 协议隔离 | 同 endpoint/engine 的 AES128、AES256、SHA256、SHA512 组合选择各自完整长度的本地化密钥，切回原组合保持热缓存 |

全部 11 项测试见 `gateway/src/test/java/com/jw/snmpdriver/snmp/runtime/Snmp4jKeyCacheTest.java`。

## 执行结果

执行目录为本工作树根目录：

```text
/home/sheon/orca/workspaces/snmp-driver/m0-7-key-cache
```

先按红绿分片运行定向测试：

```text
bash /home/sheon/orca/workspaces/snmp-driver/m0-7-key-cache/gradlew :gateway:test -x :web-ui:viteBuild --tests '*Snmp4jKeyCacheTest' --console=plain
```

合入集成分支后的完整命令：

```text
bash /home/sheon/orca/workspaces/snmp-driver/m0-7-key-cache/gradlew :gateway:test :base:test -x :web-ui:viteBuild --console=plain
```

结果为 `BUILD SUCCESSFUL`，gateway 64 项、base 10 项，失败、错误和跳过均为 0。计数来自两个模块 `build/test-results/test/TEST-*.xml` 的最终结果，其中 key-cache 测试为 11 项。

已先运行 `igdev status`。本工作树没有 `igdev.toml`，状态为未初始化，未修改 Gateway。验证没有执行 `:web-ui:viteBuild`；Gradle 依赖图中的空 web-ui Java tasks 与已有 jar task不涉及前端构建。

## 证据与限制

- 已读取 `docs/SNMP_DRIVER_V2_PLAN.md` 的 M0-7/Q16 和 `docs/research/M0S_CONTRACT_FINDINGS.md` 的 §5.2/§10。
- 已先运行 Context7 library ID 查询与 docs 查询。其结果不覆盖 3.12.2 的全部 DirectUserTarget 细节，因此固定版本契约以本机 `snmp4j-3.12.2-sources.jar` 展开的官方源码为依据：`DirectUserTarget#setAuthoritativeEngineID/duplicate`、`MessageDispatcherImpl#configureAuthoritativeEngineID`、`Snmp.ReportProcessor`、`MPv3#prepareOutgoingMessage/prepareDataElements/extractScopedPDU`、`UsmSecurityStateReference#applyTargetSecurityInformation`。
- 这些进程内回归测试证明当前固定版库的行为，不替代 net-snmp、真实 Device 或 Ignition Gateway 互通验证。
- 未运行 Gateway、Docker、风暴、web-ui、完整 build 或 CPU 基准；不报告 CPU 加速百分比。Gradle 输出包含既有 Java 路径探测及 deprecated API/Gradle 提示，未阻止测试。
- 不包含 Trap/Inform、MIB、Walk、M0-9 或 Issue/PR 写操作。报告未记录任何口令或密钥值。
