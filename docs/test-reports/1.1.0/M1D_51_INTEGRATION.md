# #51 集成验收

日期：2026-10-28。分支：m1d/release。合并提交：c538c8a（无冲突合并 feat/51 的 9c38cba）。

## 范围与性能决策

本票合入风暴测试的 handler 开关、no-op 夹具及逐条处理手册；未修改模块 Java 代码。
风暴数据沿用 M1D_43_STORM.md 的 #51 实测：2000 trap/s × 5 分钟，
sink 组 612947 条 Record 全部投递、无错误/队列丢弃，逐设备对账成立；
轮询 p95 +180.7%（sink）/+261.7%（no-op），未达到 <20% 阈值，约 0.33% 包在内核 UDP 缓冲丢弃。
负责人已接受该限制并批准先合并，1.1.1 #52 跟踪按 ScriptManager 实例缓存解析函数与项目检查的优化。
手册 §14.8 已公开当前容量参考，不视为原性能阈值通过。

## 单元测试

从 repo root 执行 bash /home/sheon/snmp-driver/gradlew :base:test :gateway:test --console=plain：
BUILD SUCCESSFUL in 10s，17 项 UP-TO-DATE，复用现有最新测试结果。
JUnit XML：base 13、gateway 326，失败/错误/跳过全部为 0。

## 热更新测试修正

首轮 record 集 10/11，通过数据为 system-test/out/results-20261028T103821.json；
script-hot-update 在项目导入返回后立即发包仍观察到 v1。
原样单独重跑 1/1 通过（results-20261028T103842.json），说明不能把导入命令返回当成脚本已重载的同步边界。
按协调者要求修正用例：实际导入 v2 后发送新的 Trap，最多观察 15 秒，首次出现 v2 后再发三条并要求不再出现 v1；
所有 Record 连续按序且逐条对账，不重启 Device/Gateway，不重试已接受 Record。
Gateway 应用项目保存后新脚本生效；观测延迟包含导入、发送和读取开销，不是纯内部重载时间。

## 修正版 Record 集

11/11 通过，结果 system-test/out/results-20261028T104247.json。
热更新首次观察 v2：1.767 s；共五条连续 Record，v2 后三条无回退。
1000 条快速 Trap 全部按序投递；容量 100 接受 150 条时保留 1–101、丢弃后到 49 条；
异常后继续、缺失/禁用/inheritable 项目、缺失函数、快照一致性、多设备和未配置路径均通过。
修正前整集第二轮也为 11/11（results-20261028T104050.json），保留作为时序问题的辅助证据。

## 全量回归与环境清理

全量系统测试 60/60 通过，结果 system-test/out/results-20261028T105744.json。
覆盖 v1/v2c/v3、Inform 防重放、Gateway 重启、模块重装、端口冲突恢复、轮询、Docker UDP 映射、SET 和全部 Record 用例。
本轮首次观察 v2 为 4.385 s；之后三条无 v1 回退，五条序号连续。
本次未重新执行 2000 trap/s 风暴，性能数据为合入分支已有三组实测，未把未达标项改记为通过。

Gateway 使用 igdev / Ignition 8.3.10，挂载 com.jw.snmpdriver-1.1.0-m51-dev.unsigned.modl；
模块源码与合并基线相同，仅本地忽略产物带 License bypass，本次无需重新安装。
不提交 bypass、.scratch、凭据或原始运行产物。

最终清理完成：测试设备列表为空，st-records 恢复 v1/normal，sink 为 []；Gateway ensure 和 OPC UA 可用，MAC 实测固定为 02:42:ac:12:00:62。
