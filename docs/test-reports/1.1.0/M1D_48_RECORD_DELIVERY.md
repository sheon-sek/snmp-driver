# #48 Record Delivery 基本投递验收

日期：2026-10-27。Gateway：Ignition 8.3.10，igdev，固定 MAC `02:42:ac:12:00:62`。

## 本次范围

新增 Device 的 Record Handler Project / Record Handler Function 配置，接受 Notification 后异步调用项目 Script Library；每个 Device 保持 FIFO、一次一条，不改变 Snapshot、Inform ACK 和轮询路径。公开 `Traps/RecordDeliveredCount`，未配置函数时不产生 Record。队列容量、失败/丢弃计数、慢调用和压力验收属于 #47 后续票，本次不宣称已完成整个 #47。

## 真实 SDK 结论

Task A 在同一 8.3.10 Gateway 用普通 Java 后台线程实测公开 SDK：

- 不存在项目的 `getProjectScriptManager` 返回 null；禁用项目及 inheritable 项目仍返回非空 manager，且能实际调用函数。因此正式实现先通过 `getManifests()` 检查存在、enabled 且非 inheritable。
- `createLocalsMap` + `runCode` 解析函数，再用 `runFunction` 传入单个 Unicode JSON 字符串；参数不拼接到代码中。解析阶段的缺失模块/属性及 `PySyntaxError` 归 NOT_FOUND；已解析函数执行时抛出的异常归 HANDLER_FAILED，包括业务函数自行抛出的 NameError / AttributeError。
- 整项目导入与只保存 Script Library 单文件并触发项目扫描后，旧 manager 已关闭，再使用会抛 NPE；重新获取 manager 后下一次调用立即使用新代码。正式实现逐条获取 manager、locals 和函数，不缓存。并发保存和调用仍存在 SDK 生命周期竞态，本次不承诺原子热切换。
- 普通 Java 后台线程正常调用，exception 与 slow 夹具也按预期运行，不需要切换到 Designer / Swing 线程。

## 系统验收

定向 `python3 /home/sheon/snmp-driver/system-test/st.py run record`：3/3 通过，结果 `system-test/out/results-20261027T172002.json`（忽略的运行产物）。

| 用例 | 结果 |
|---|---|
| record:ten-at-500ms | 10 条间隔 500 ms，单个 OPC UA 会话连续读取捕获 10 条对应 Last/Json；处理函数调用 10 次，顺序 1–10，参数逐条等于快照加 device，DeliveredCount=10 |
| record:multi-device | 同一 Notification 命中两个 Device，各投递一次，device 归属及每个 Device 的快照内容一致 |
| record:unconfigured | 未配置函数的 Device 仍接受并更新快照，DeliveredCount=0，sink 只有已配置对照 Device 的一条 Record |

全量 `python3 /home/sheon/snmp-driver/system-test/st.py run`：52/52 通过，结果 `system-test/out/results-20261027T173436.json`（忽略的运行产物）。包含全部现有 v1/v2c/v3、路由、监听冲突、重放、重启、模块生命周期、轮询、Docker UDP 映射、SET 及三条 Record 用例。

## 单元测试

从 repo root 执行 `bash /home/sheon/snmp-driver/gradlew :base:test :gateway:test --console=plain`，最终 BUILD SUCCESSFUL（1m34s）。JUnit XML：base 13、gateway 311，失败/错误/跳过均为 0。覆盖 Device 接线的十条顺序投递、同一 Device 一次一条、多 Device 各一条、未配置不调用、旧 JSON 默认值、配置格式校验、DeliveredCount 节点，以及 SDK 项目 manifest 拒绝和 PySyntaxError 分类。

语法错误单测初始化 Jython 核心，通过代理 ProjectManager 抛出实际 PySyntaxError 类型来验证不可用项目的归类。真实 Script Library 解析/执行行为由 Task A 的 Gateway SDK 实测及端到端系统用例验证。测试生成的 Jython 缓存移到忽略的 `.scratch`，未提交。

## 构建与清理

正式源码不含 License bypass。当前源码复制到 `.scratch/devbuild-taskc` 后单独应用本地 bypass patch，构建 `com.jw.snmpdriver-1.1.0-taskc-dev.unsigned.modl` 安装到测试 Gateway；该产物未签名，仅供开发验收，不提交。igdev 0.3.1 更换模块文件名时首次 restart 遇到旧 mount 路径错误，随后 `gateway ensure` 重建 compose 并重新固定 MAC 恢复，数据卷保留。

测试结束后已删除所有 st-* 测试设备，设备列表为空，恢复 st-records normal 并清空 sink；Gateway ensure 成功，MAC 实测仍为 `02:42:ac:12:00:62`。
