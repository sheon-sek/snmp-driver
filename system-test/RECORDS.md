# Notification Record 系统测试夹具（#48 / #50）

`projects/st-records/` 是手写的最小 Ignition 8.3 测试项目，不含凭据、Gateway 配置导出或 License。
Gateway 项目名为 `st-records`，Script Library 函数为 `snmp.handlers.onNotification(record)`。
参数原样写入 Gateway 容器的 `/tmp/snmp-driver-st-records.jsonl`，每行包含版本标签及原始 Record JSON 字符串。
这只是系统测试用的文件 sink，不属于模块运行时，也不会解析业务字段。

从 repo root 的 WSL shell 运行：

```bash
python3 - <<'PY'
import sys
sys.path.insert(0, "/home/sheon/snmp-driver/system-test")
import st

st.import_record_project()  # 首次创建；已存在时拒绝覆盖
st.set_record_handler("normal")
st.clear_records()          # 必须先停发包，等待队列排空和处理函数退出
# 创建设备时配置：
# recordHandlerProject="st-records"
# recordHandlerFunction="snmp.handlers.onNotification"
# 发包后：
print(st.read_records())                    # 按追加顺序返回解码后的 Record
print(st.read_records(with_metadata=True))  # [{"version": "v1", "record": "<原始 JSON>"}]
PY
```

夹具已存在且确认可替换时，用 `st.import_record_project(overwrite=True)`。
它只替换这个测试项目；不重建 Gateway，不修改其他项目、设备或 Tag。

#50 可使用的变体：

```python
st.set_record_handler("exception")             # ValueError("ST_RECORD_HANDLER_ERROR")，不追加记录
st.set_record_handler("slow", delay_millis=6000) # 先等待，再追加；默认 6000 ms
st.set_record_handler("normal")                # 恢复正常，移除版本标签覆盖
st.set_record_handler("normal", version="label") # 仅改变记录标签，不是脚本热更新
st.import_record_project(overwrite=True, version="v2") # 修改实际脚本 VERSION，测试热更新
st.import_record_project(overwrite=True, version="v1") # 恢复原版
```

配置通过原子文件替换生效，已开始的调用保留开始时读取的配置。项目保存不会清空已采集的记录。
`read_records()` 只返回完整行；并发追加时尚未完成的最后一行留给下一次读取。
多个 Device 共用此项目时使用一个 Gateway 全局锁串行追加；跨 Device 的行序只是实际执行顺序，不代表设备间接收顺序。
`clear_records()` 仅截断该测试文件，不能与正在执行的 handler 并发。
文件不设大小上限；每个用例开始前在静止状态清空，风暴测试结束后及时清空。
`/tmp` 文件不保证跨容器重建保留，测试必须自行初始化。
正常 handler 返回版本标签；驱动可以忽略返回值。

`snmp.handlers.onNotificationNoop(record)` 只返回版本标签，不读 control 文件、不写 sink。
风暴测试（#51）用它测量驱动自身的逐条调用开销：`storm.py run --handler 2`；`--handler 1` 使用上面的正常 sink。

SDK 实测由临时项目从普通 Java 线程调用上述函数完成，正常、异常、50 ms 慢调用和读/清空均已通过。
本任务没有修改模块源码或安装 bypass，也没有执行 Trap 到 Record Delivery 的端到端用例；后者交由 #48/#50 集成测试。


## #50 异常与配置用例

`cases.py` 新增 `record:handler-exception-continues`、`record:missing-project`、
`record:missing-function`、`record:disabled-project`、`record:inheritable-project`、
`record:script-hot-update`。每个用例检查终态计数与对账式，异常用例确认后续序号可继续；
热更新用例修改实际 Script Library 的 VERSION，Gateway 异步应用项目保存后生效：导入 v2 后发送新的 Trap，最多观察 15 秒，要求出现 v2 后不再出现 v1，且所有 Record 序号连续、每条只投递一次。测试不重启 Device/Gateway，不重试已接受的 Record；报告记录从导入开始到首次观察 v2 的延迟。

`import_record_project(overwrite=True, enabled=False)` 和
`import_record_project(overwrite=True, inheritable=True)` 生成不可运行项目变体；
默认调用恢复 enabled=true、inheritable=false。用例 finally 恢复正常项目并清除自身设备。

#50 implementer 只完成 Python 语法检查，没有运行上述用例或全量系统回归；由 merger 在共享 Gateway 串行验证。

#51 集成实测：修正版 Record 集首次观察 v2 为导入开始后 1.767 s；之后三条保持 v2，序号连续。该时间包含导入命令、发包和读 sink 开销。
全量 60/60 回归中同一观测延迟为 4.385 s；这两次数据不构成固定延迟保证。


## #53 License PENDING

新增 `restart:license-pending-traps`：Gateway 重启后，在确认 listener Listening 的第一时间发送 10 条 Trap，
要求 Received=Accepted=RecordDelivered=10、序号 1..10、所有丢弃计数为零。
返回值明确记录是否观察到“License 验证中”暂存提示；未观察到时只算重启 smoke，不宣称覆盖 PENDING。
现有生产构建没有可控的 License 验证延迟，bypass 构建也可能直接返回 LICENSED；
需要 merger 在隔离的开发构建中制造判定延迟，或明确使用下面的确定性 JUnit 证据。

`SNMPDevicePendingLicenseTest` 使用真实 UDP listener、Device、快照与 Record Delivery，仅控制 License 状态及项目函数外部边界：
暂存后放行并保持 payload/Record 顺序、判无效全部丢弃、原有 10 000 条队列溢出、
Inform PENDING 不 ACK/不暂存且有效后重发正常接受，均在此层验证。
`PendingInformCount` 记录未 ACK 的 PENDING Inform 尝试，`ReceivedCount` 按接收尝试计数；
队列排空后 Received 等于 Accepted、三种 Dropped、DuplicateInform 和 PendingInform 之和。
暂存中的 Trap 尚无终态，暂存条数要加到等式右侧。

本 implementer 只编写系统用例并做 Python 语法检查，不访问 igdev Gateway，也未运行定向或全量系统回归；
由 merger 串行执行 `python3 system-test/st.py run restart:license-pending-traps` 和全量回归。
