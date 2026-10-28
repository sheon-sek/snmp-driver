# PR #46 审查修复集成验收

日期：2026-10-28。环境：WSL Ubuntu-24.04、Ignition 8.3.10、igdev Gateway。

## 代码与构建

- 集成分支：`m1d/release`。
- 修复提交：`fa525211935bdd973e4ed90d1c7a5f15b2caaa07`。
- 合并提交：`48c2d71`，`git merge --no-ff feat/review-fixes`，无冲突。
- 正式源码没有应用 License bypass；将当前源码复制到忽略的 `.scratch/devbuild-review-fixes` 后，仅在副本应用 `.scratch/license-bypass.patch`。
- 从 repo root 执行 `bash /home/sheon/snmp-driver/gradlew -p /home/sheon/snmp-driver/.scratch/devbuild-review-fixes build -x test --console=plain`，构建成功（38 秒）。安装文件名：`com.jw.snmpdriver-1.1.0-review-fixes-dev.unsigned.modl`，仅供开发测试，未签名。
- igdev 更换模块文件名时遇到已知旧 mount 错误；通过 `st.recreate_gateway` 保留数据卷重建容器，再用 `st.pin_gateway_identity` 固定 MAC `02:42:ac:12:00:62`。模块 ACTIVE、OPC UA 可访问后开始测试。

## 九条审查项处理

| 项 | 审查问题 | 处理 |
|---|---|---|
| 1 | License PENDING 时 Status 显示 Listening，但 Notification 被按 Unlicensed 丢弃、Inform 不 ACK | 待负责人决定，本次未改；bypass 功能测试不能证明该状态行为 |
| 2 | 同名 Device 新实例阻塞等待旧实例的 CALL_LOCKS，占满共享线程池 | 已修：CallGate 停放等待实例，旧调用结束后重新调度，不占共享工作线程 |
| 3 | HANDLER_FAILED 每条 WARN，无去重 | 已修：连续相同失败只警告一次，成功后重置，计数仍逐条累计 |
| 4 | Record lastError 成功后不清除 | 已修：成功投递后清除 Record 错误 |
| 5 | flushCounters 因 Record 错误变化 refresh，抹掉接收侧 notice；handle=null 时错误不可见 | 已修：统一 publishLastError，计数刷新不再抹掉 notice，handle=null 也发布 Record 错误 |
| 6 | notice 拼接 LastError 漏掉 configNotice | 已修：统一拼接状态原因、接收提示、配置提示、身份提示和 Record 错误 |
| 7 | 每条读取 manifest、获取 ScriptManager、runCode 解析，开销较高 | 转 #52（1.1.1）；沿用 #51 性能结论，本次没有重跑风暴或宣称解决性能问题 |
| 8 | static CALL_LOCKS 永不清理；改名后使用不同锁 | 已修：调用结束且无等待实例时释放状态；改名视为新 Device 的边界写入 ADR 0010 |
| 9 | SNMPv3_USM_PARSE_ERROR 被提示为解密失败 | 已修：单独提示安全参数无法解析 |

七项修复均包含在 `fa52521`，由本次合并纳入集成分支。没有关闭 issue。

## 单元测试

从 repo root 执行 `bash /home/sheon/snmp-driver/gradlew :base:test :gateway:test --console=plain`，BUILD SUCCESSFUL（2 分 1 秒）。JUnit XML 合计 base 13、gateway 334，失败、错误、跳过均为 0。包含 CallGate 等待不阻塞共享执行器、状态释放、重复失败日志、成功清错、诊断消息保留及 USM 解析提示覆盖。

## Gateway 定向测试

命令：`python3 /home/sheon/snmp-driver/system-test/st.py run record v3:wrong-auth-pass v3:wrong-priv-pass v3:wrong-engineid v3:wrong-user v3:inform-wrong-priv-pass`。

16/16 通过（11 条 Record、5 条 v3 认证失败），结果文件：`system-test/out/results-20261028T112310.json`（忽略的运行产物）。

- 高频：1000 条 Trap → 1000 次调用，序号 1–1000 有序连续。
- 溢出：Accepted=150、Delivered=101、Dropped=49，保留 1–101，丢弃最新 49 条，对账成立。
- 10 条间隔 500 ms：调用 10 次，每条 Record 等于对应 Last/Json 加 device。
- 异常后继续、项目/函数缺失、禁用/inheritable 项目、多 Device、未配置均通过。
- 热更新首次 v2：导入开始后 3.104 秒；后续不回退，5 条 Record 有序。本观测包含导入、发包及读取开销，不是固定延迟保证。
- v3 错误认证口令、隐私口令、engineID、用户名、Inform 隐私口令均拒绝并显示相应 LastError。

## 全量回归与清理

从 repo root 执行 `python3 /home/sheon/snmp-driver/system-test/st.py run`，60/60 通过，结果文件：`system-test/out/results-20261028T114422.json`（忽略的运行产物）。用例耗时合计 1272.3 秒；定向 16 条合计 176.4 秒。全量包含 v1/v2c/v3 正常互通、认证失败、路由、监听冲突、禁用/启用、Inform 重放、Gateway 重启、模块重装、端口占用恢复、轮询、Docker UDP 映射、SET 与全部 Record 用例。

全量热更新首次 v2 为导入开始后 3.268 秒，后续没有回退；5 条 Record 按序。Gateway 重启保持 engineID，engineBoots 1→2；端口占用解除后 21 秒恢复。两次 Record 集及全量均未发现集成问题，因此除合并和验收报告外没有新增运行时修改。

最终清理：所有测试设备已删除（设备列表 `[]`），`st-records` 恢复 v1、normal；等待处理函数结束后清空 sink，读取结果 `[]`。`igdev gateway ensure` 成功，OPC UA 可访问，Gateway 可用，实测 MAC `02:42:ac:12:00:62`。单测生成的 Jython 缓存移入忽略的 `.scratch`；bypass、构建目录、模块产物和运行日志均未纳入提交。

本次仅证明开发 bypass 模块的上述功能回归，不作为正式 License 状态或生产签名 artifact 验收，也不替代 #52 性能优化验收。