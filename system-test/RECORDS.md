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

SDK 实测由临时项目从普通 Java 线程调用上述函数完成，正常、异常、50 ms 慢调用和读/清空均已通过。
本任务没有修改模块源码或安装 bypass，也没有执行 Trap 到 Record Delivery 的端到端用例；后者交由 #48/#50 集成测试。
