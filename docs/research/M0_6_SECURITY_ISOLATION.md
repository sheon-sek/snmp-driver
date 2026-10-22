# M0-6 安全模型隔离

对应 [#10](https://github.com/sheon-sek/snmp-driver/issues/10)，父规格 [#4](https://github.com/sheon-sek/snmp-driver/issues/4)。

## 实现范围

`Snmp4jRequestClient` 为每个客户端创建独立的 `SecurityProtocols`、`SecurityModels`、USM 和 MPv3。五参 MPv3 构造注入同一组实例，`DirectUserTarget` 从客户端的协议表取得算法对象。现有算法、每请求新 target、engine boots 初始化及请求预算不变，本任务未加入 #11 的本地化密钥缓存。

通过 `Snmp(MessageDispatcher, TransportMapping)` 创建会话，避免便利构造调用 `initMessageDispatcher()` 时初始化全局协议表。客户端显式添加 MPv1、MPv2c 和实例 MPv3，不向全局 `SecurityModels` 注册、移除或覆盖 USM，也不更换全局 `SecurityProtocols`。transport factory 继续返回未配置模型的会话，原有 IPv6 回退及失败清理测试沿用该契约。

## 残余全局依赖

以下结论核对了 [SNMP4J 3.12.2 官方源码包](https://repo.maven.apache.org/maven2/org/snmp4j/snmp4j/3.12.2/snmp4j-3.12.2-sources.jar)，不是全库隔离承诺。

| 路径 | 残余依赖与影响 |
| --- | --- |
| `org/snmp4j/security/UsmSecurityParameters.java:215–230`，`USM.java:894–895` | `getBERMaxLength()` 读取全局协议表的最大认证摘要及加密参数长度，用于响应 scoped PDU 尺寸估算。实例协议表不能消除该读取，也不能据本次测试保证所有报文尺寸下均准确。 |
| `org/snmp4j/mp/MPv1.java:195`，`MPv2c.java:198` | 创建 StateReference 时查询全局 `SecurityModels`。这不是客户端向全局注册 USM。 |
| `org/snmp4j/security/USM.java:85–86` | 使用的三参 USM 构造继续取得全局 `CounterSupport`。 |
| `org/snmp4j/Snmp.java:312–322` | 显式 dispatcher 构造在启用 SNMP4J statistics 时仍取得全局 `CounterSupport`。 |
| `Snmp4jRequestClient` 的五参 MPv3 构造 | 显式传入全局 `CounterSupport`，维持共享计数语义；未承诺计数器隔离。 |

五参 MPv3 的注入契约见 `org/snmp4j/mp/MPv3.java:169–195`；显式 dispatcher 构造不自动安装模型见 `Snmp.java:281–322`。Context7 已先解析库 ID 并查询文档，但返回旧版全局注册示例，因此固定版本契约采用上述源码及 [M0-S §5.1](M0S_CONTRACT_FINDINGS.md#51-隔离-mpv3-后的全局读取)。

## 回归证据

`Snmp4jSecurityIsolationTest` 通过现有 transport factory 捕获真实会话，不新增生产测试接口。全局 USM 保护测试先在修改前失败，失败原因是既有 USM 对象被覆盖；实现隔离后通过。测试临时安装的全局对象在 finally 中恢复，不属于客户端行为。

新增 6 项测试调用：

- 两个客户端创建、启动、关闭前后，全局既有 USM 身份和协议注册映射保持不变。
- 5 组真实 loopback UDP v3 GET。两个独立设备使用相同用户名和不同测试口令，客户端在另一客户端创建前、并存时以及关闭后都能收到响应。并存阶段两个请求同时在途。
- 测试组合为空全局协议表下的 noAuthNoPriv、SHA256 authNoPriv、SHA256/AES128 authPriv、SHA512/AES256 authPriv，以及默认全局协议表下的 SHA512/AES256 authPriv。
- 每个设备返回不同的 600 多字节字符串，并断言收到的 OID、值和 GET 类型；同时检查客户端 USM、MPv3、协议表、模型表彼此独立。

定向命令从工作树根执行：

```sh
bash /home/sheon/orca/workspaces/snmp-driver/m0-6-security/gradlew :gateway:test --tests com.jw.snmpdriver.snmp.runtime.Snmp4jSecurityIsolationTest -x :web-ui:viteBuild
```

结果为 6 项通过。完整测试命令为：

```sh
bash /home/sheon/orca/workspaces/snmp-driver/m0-6-security/gradlew :gateway:test :base:test -x :web-ui:viteBuild
```

结果为 gateway 27 项、base 10 项通过，失败、错误和跳过均为 0。`igdev status` 已执行，当前工作树无 `igdev.toml`；没有修改 Gateway 或运行系统验证。

这些测试证明当前固定版本库的实例隔离回归和选定参数下的 GET，不替代 net-snmp、真实设备或 Gateway 互通，也未验收接近消息上限的所有响应尺寸、跨模块全局计数器、engineID 更换和 #11 缓存行为。`maxRequestBytes=1200` 仍是既有请求预算，不是本次新增的编码报文硬上限。
