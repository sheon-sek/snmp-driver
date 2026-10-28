# M1d-4 (#43) 风暴测试报告

日期：2026-10-26。分支：`m1d/release`（HEAD 6afdfa9 + 本票测试脚本，模块源码未改）。模块：`1.1.0-beta4` dev 构建。脚本：`system-test/storm.py`、`system-test/storm_box.py`。原始数据：`system-test/out/storm-20261026T162110/`（不入库）。

结论是本机单次运行的参考值（Q33），不是性能规格。

## 结论

| 阈值（计划 §6.4） | 结果 | 数据 |
|---|---|---|
| 2000 trap/s × 5 min：轮询 p95 延迟增幅 < 20% | **通过** | p95 0.902 → 0.964 ms（+6.9%） |
| 10000 trap/s × 30 s：轮询 p95 延迟增幅 < 20% | **未通过，负责人已接受** | p95 0.902 → 1.352 ms（+49.9%，绝对值增加 0.45 ms） |
| 不新增轮询超时 | **通过** | 全程 77 k 个 GET，0 次重传，OPC UA 上 0 个 Bad 值，熔断器全部 CLOSED |
| 未被接受的 Trap 都能在丢弃计数里对上账 | **通过** | 各阶段 `Received = Accepted + DroppedRateLimit + DroppedQueueFull + DroppedUnlicensed + DuplicateInform`，差值 0；没到模块的包与 Gateway 内核 `UdpRcvbufErrors` 一一对上 |
| 堆内存在队列上限内平稳 | **通过** | 各阶段在 286–885 MiB 之间锯齿波动（上限 2048 MiB），GC 后低点没有上升趋势 |
| 队列满时 Inform 不 ACK | **通过**（在超规格的 flood 阶段观测） | ACK 379 = Accepted 379；被队列拒绝的 31 个与内核丢弃的 89 个都没有 ACK |

10000 trap/s 突发没达到 20% 阈值，按约定不调整阈值，如实记录。说明：

- 基线 p95 只有 0.9 ms，20% 只相当于 0.18 ms。突发后 2 分钟、已经没有 Trap 的恢复阶段 p95 也比基线高 24.9%（1.127 ms），说明这个量级下延迟受主机整体负载和调度噪声影响很大，不完全是 Trap 路径造成的。
- 轮询节奏本身没受影响：每台设备每秒 1.00 个周期，周期间隔 p95 在突发时为 1001.8 ms（基线 1001.3 ms），没有漏轮询，没有超时。
- 发包器与 Gateway 在同一台笔记本上，突发时发包器单核占满，会和 Gateway 抢 CPU。

**负责人决定（2026-10-26）：接受这一结果，不复测。** 理由：绝对增量只有 0.45 ms；没有新增超时、重传或漏轮询；突发结束后已无 Trap 的恢复阶段仍比基线高 24.9%，说明增幅主要来自同机发包与主机调度负载，而不是模块的 Trap 路径；2000 trap/s 持续阶段（+6.9%）达标。

## 环境

| 项目 | 值 |
|---|---|
| 硬件 | Intel Core Ultra 7 165U（14 逻辑核，笔记本低功耗 U 系列），31 GiB 内存 |
| 系统 | Windows 11 + WSL2 Ubuntu 24.04.4（内核 6.18.33.2-microsoft-standard-WSL2） |
| Docker | 29.8.1，容器无 CPU/内存限制 |
| Gateway | igdev 0.3.1，Ignition 8.3.10，Azul OpenJDK 17.0.20.1，最大堆 2048 MiB |
| 模块 | 1.1.0-beta4 dev 构建（developer-mode License bypass，补丁不入库），Trap 代码与入库源码一致 |
| 发送方/agent | `snmp-systest` 容器（net-snmp 5.9 `snmpd` + Python 3.12），与 Gateway 在同一 igdev Docker 网络 |

## 方法

**负载**：50 台 v2c Device，每台 25 个 OID（`storm.py` 的 `OIDS`），1 s 轮询。每台设备在 `snmp-systest` 上有自己的 IP 别名（172.18.250.1–50），轮询和 Trap 都按源 IP 归属到各自的设备。设备 0–44 收 Trap，45–49 收 Inform（每台 10 个/s，独立于 Trap 速率）。为了让全部流量进入共享队列，`trapRateLimitPerSecond` 设为上限 10000，因此本次 `DroppedRateLimitCount` 恒为 0。

**轮询**：一个 OPC UA 订阅（采样 1000 ms）订阅全部 1250 个 Item，由 Gateway 发起轮询。驱动按 `maxRequestBytes=1200` 把每台设备的 25 个 OID 拆成约 3 个 GET。

**阶段**：基线 180 s（无 Notification）→ storm 300 s @ 2000 trap/s → burst 30 s @ 10000 trap/s → flood 10 s @ 120000 trap/s（超出 §6.4，用来打满队列）→ 恢复 120 s。

**发包器**：net-snmp `snmptrap` 每个包起一个进程，远达不到目标速率。`storm_box.py send` 把 v2c Trap/Inform 预编码成 BER，再用 UDP socket 直接发送，并按请求 ID 统计 Inform ACK。实测速率：

| 阶段 | 目标 | 实测 | Trap 总数 | 发送错误 |
|---|---|---|---|---|
| storm | 2000/s | 1999.9/s | 599 999 | 0 |
| burst | 10000/s | 9997.3/s | 299 999 | 0 |
| flood | 120000/s（3 个进程） | 118 161/s | 1 199 930 | 0 |

**测量**（都在模块外部，不新增模块 API）：

- 轮询延迟 = Gateway 给 `sysUpTime.0` 值打的 SourceTimestamp（驱动收到 GET 响应的时刻）− agent 侧 pcap 里携带 `sysUpTime.0` 的那个 GET 的抓包时刻。两个容器共用同一内核时钟。ServerTimestamp 被 Ignition 在采样时重写，不能用。
- 超时：pcap 里同一设备同一 request-id 的重复 GET（SNMP 重试），以及 OPC UA 上收到的非 Good 值。
- 对账：每个阶段前后用 OPC UA 读取 50 台设备的 `Traps/*` 计数；Gateway 容器的 `/proc/net/snmp`（`RcvbufErrors`）用来解释没进模块的包。
- 堆：每 5 s 读 Gateway REST `/data/api/v1/overview` 的 `memory`。

## 轮询延迟（ms）

| 阶段 | 样本 | p50 | p95 | p99 | max | p95 增幅 |
|---|---|---|---|---|---|---|
| 基线 | 9 000 | 0.393 | 0.902 | 1.304 | 6.608 | — |
| storm 2000/s | 15 000 | 0.374 | 0.964 | 1.486 | 4.872 | +6.9% |
| burst 10000/s | 1 500 | 0.448 | 1.352 | 2.148 | 12.021 | +49.9% |
| flood 120000/s | 500 | 0.697 | 2.422 | 4.941 | 6.742 | +168.5% |
| 恢复 | 6 000 | 0.487 | 1.127 | 1.502 | 3.006 | +24.9% |

周期间隔（同一设备相邻两次携带 `sysUpTime.0` 的 GET）：p95 基线 1001.3 ms、storm 1001.3 ms、burst 1001.8 ms、flood 1005.9 ms、恢复 1001.5 ms；各阶段每台设备每秒都是 1.00 个周期。GET 重传 0，Bad 值 0。

## 对账

| 阶段 | 发送（Trap + Inform） | Received | Accepted | DroppedQueueFull | 其他丢弃 | 设备内差值 | 没到模块 | Gateway `UdpRcvbufErrors` |
|---|---|---|---|---|---|---|---|---|
| storm | 599 999 + 14 999 | 614 998 | 614 998 | 0 | 0 | 0 | 0 | 0 |
| burst | 299 999 + 1 499 | 301 498 | 301 498 | 0 | 0 | 0 | 0 | 0 |
| flood | 1 199 930 + 499 | 957 423 | 555 537 | 401 886 | 0 | 0 | 243 006 | 243 006 |

AuthFailure、ListenerUnmatched、DuplicateInform 各阶段都是 0。flood 时 Gateway 套接字接收缓冲区溢出，内核丢掉的 243 006 个包到不了模块，因此不会出现在任何 Device 计数里；这部分只能在操作系统层面看到（`netstat -su` / `/proc/net/snmp` 的 receive buffer errors）。进入模块的每个包都有去处。

## Inform

| 阶段 | 发送 | ACK | 未 ACK | 设备 Accepted | 设备丢弃（队列满） |
|---|---|---|---|---|---|
| storm | 14 999 | 14 999 | 0 | 14 999 | 0 |
| burst | 1 499 | 1 499 | 0 | 1 499 | 0 |
| flood | 499 | 379 | 120 | 379 | 31 |

flood 时：ACK 数与入队数完全相等；31 个被队列拒绝的 Inform 没有 ACK；另外 89 个被内核丢弃，既不计数也不 ACK（发送方会重试）。storm 和 burst 下队列没有满过（worker 能处理 10000/s 以上）。

## 堆内存（MiB，上限 2048）

| 阶段 | 最低 | 最高 | 平均 |
|---|---|---|---|
| 基线 | 320 | 781 | 567 |
| storm | 286 | 885 | 586 |
| burst | 407 | 873 | 660 |
| flood | 510 | 510 | 510（只有 1 个采样） |
| 恢复 | 314 | 875 | 588 |

整个过程是正常的 GC 锯齿，每轮 GC 后都回到约 290–410 MiB，没有持续上升。队列上限 10 000 条，按单条 Notification 约 1–2 KiB 估算，满队列也只占十几 MiB。

## 观察（未改代码）

- 每台设备的 `Diagnostics/LastError` 留下 `attribute: Unsupported dynamic SNMP attribute=EventNotifier`。这是 OPC UA 客户端（asyncua）建立订阅时读取了 Variable 节点不存在的 EventNotifier 属性，驱动正确返回 `Bad_AttributeIdInvalid`，同时把它记成了设备最近一次失败。和风暴负载无关，不影响轮询；是否让这类属性读取不写 `LastError`，留给后续决定。
- 25 个 OID 按 1200 B 拆成约 3 个 GET/设备/周期，全程每秒约 150 个 GET。

## 复现

```bash
python3 system-test/st.py setup          # 重建 systest 容器（NET_ADMIN，用于 IP 别名；镜像含 tcpdump）
python3 system-test/storm.py run --baseline 180 --storm 300 --burst 30 --flood 10 --recovery 120
python3 system-test/storm.py cleanup     # 删除 st-* 设备、撤掉 IP 别名
```

全程约 13 分钟，结束时打印 `summary.json`。

## #51 开启处理函数的重跑（2026-10-27）

### 结论

| 阈值 | 结果 | 数据 |
|---|---|---|
| Record 对账式成立（队列排空后） | **通过** | 两组均 `AcceptedCount = RecordDeliveredCount + RecordHandlerErrorCount + RecordDroppedCount + RecordQueueDepth`，50 台设备逐台差值 0；sink 组 612 947 = 612 947 + 0 + 0 + 0，sink 文件行数 612 947 |
| 2000 trap/s × 5 min：轮询 p95 增幅 < 20% | **未通过** | sink 处理函数 0.969 → 2.720 ms（+180.7%）；no-op 处理函数 1.128 → 4.080 ms（+261.7%） |
| 不新增轮询超时 | **通过** | GET 重传 0，Bad 值 0，熔断器全部 CLOSED |
| Trap 全部进入模块 | **未通过（新观察）** | 两组各有约 2 000 个包（0.33%）在 Gateway 内核 UDP 接收缓冲溢出，未到模块，其中 52 / 47 个 Inform 未 ACK；同一构建不配处理函数的对照组为 0 |

开启处理函数后，Record 不丢、全部投递并对上账，但 2000 trap/s 持续负载下轮询 p95 大幅超过 20% 阈值，并出现内核 UDP 丢包。同一构建、同一时段不配处理函数的对照组 p95 未上升、无丢包，因此退化来自 Record 的调用路径本身，不是主机噪声。轮询 p95 阈值未达标，负责人已接受本次结果并批准先合并；按 ScriptManager 实例缓存解析函数与项目检查的性能优化移至 1.1.1 的 #52。本票未改模块代码，当前结果不代表满足原性能阈值。

### 环境与构建

硬件与软件同上表：Intel Core Ultra 7 165U（14 逻辑核，笔记本低功耗 U 系列）、31 GiB 内存，Windows 11 + WSL2 Ubuntu 24.04（内核 6.18.33.2-microsoft-standard-WSL2），Docker 29.8.1，igdev Gateway Ignition 8.3.10、最大堆 2048 MiB，固定 MAC `02:42:ac:12:00:62`。发包器与 Gateway 在同一台笔记本上。

模块：`feat/51`（基于 `m1d/release` 707e272，模块源码与之相同）复制到忽略的 `.scratch` 后应用本地 License bypass patch，构建为 `com.jw.snmpdriver-1.1.0-m51-dev.unsigned.modl` 安装；未签名，仅供开发验收，不提交。三组在同一次安装上连续运行，原始数据在 `system-test/out/storm-20261027T185325/`（sink）、`storm-20261027T190526/`（no-op）、`storm-20261027T191657/`（对照），不入库。

### 方法

负载、测量方法与上文相同，只跑基线 180 s → storm 300 s @ 2000 trap/s（另 Inform 10/s × 5 台）→ 恢复 60 s。`storm.py run --handler N` 给 50 台设备都配置 `st-records` 项目（`system-test/RECORDS.md`）的处理函数，结束后等全部 `RecordQueueDepth` 归零再读计数对账：

- `--handler 1`：`snmp.handlers.onNotification`（normal 模式），每条读 control 文件、`jsonDecode`、取全局锁、打开并追加写 sink 文件；
- `--handler 2`：`snmp.handlers.onNotificationNoop`，只返回版本标签，用来测量驱动自身的逐条调用开销；
- 对照：不加 `--handler`，同一构建。

### 轮询延迟（ms）

| 组 | 阶段 | 样本 | p50 | p95 | p99 | max | p95 增幅 |
|---|---|---|---|---|---|---|---|
| sink 处理函数 | 基线 | 9 000 | 0.450 | 0.969 | 1.382 | 4.438 | — |
| | storm | 14 935 | 0.635 | 2.720 | 5.246 | 95.450 | +180.7% |
| | 恢复（积压可能仍在处理） | 2 992 | 0.645 | 2.079 | 4.279 | 102.187 | +114.6% |
| no-op 处理函数 | 基线 | 9 000 | 0.454 | 1.128 | 1.698 | 25.414 | — |
| | storm | 14 905 | 0.631 | 4.080 | 7.426 | 101.709 | +261.7% |
| | 恢复 | 3 000 | 0.593 | 1.605 | 2.792 | 4.084 | +42.3% |
| 对照（无处理函数） | 基线 | 9 000 | 0.446 | 1.233 | 1.657 | 2.756 | — |
| | storm | 15 000 | 0.371 | 0.975 | 1.420 | 7.435 | −20.9% |
| | 恢复 | 3 000 | 0.519 | 1.380 | 1.790 | 6.885 | +11.9% |

周期间隔 p95：两组处理函数的 storm 阶段为 1043.6 / 1045.0 ms（p99 约 1082 ms，max 1164 / 1202 ms），对照组 1001.4 ms；每台设备每秒周期数 0.995 / 0.993（对照 0.999）。没有漏轮询意义上的超时，但轮询节奏出现最长约 200 ms 的推迟。

### Record 对账与吞吐

| 组 | Accepted | Delivered | HandlerError | Dropped | QueueDepth（排空后） | storm 结束 4 s 后积压 | sink 行数 |
|---|---|---|---|---|---|---|---|
| sink 处理函数 | 612 947 | 612 947 | 0 | 0 | 0 | 34 636 | 612 947 |
| no-op 处理函数 | 612 986 | 612 986 | 0 | 0 | 0 | 0 | 0（不写 sink） |

sink 组的处理速度低于到达速度（约 2 045 条/s）：storm 期间约 1 900 条/s，结束时积压 34 636 条（平均每台约 690 条，远低于每台容量 10 000，没有 Dropped）；最后一个包发出后 124 s 时确认全部排空（含 60 s 恢复阶段和收尾，排空时刻未精确测量）。no-op 组 4 个调用线程能跟上到达速度，没有积压。

### Trap 接收与 Inform

| 组 | 发送（Trap + Inform） | Received = Accepted | 没到模块 | Gateway `UdpRcvbufErrors` | Inform ACK / 未 ACK |
|---|---|---|---|---|---|
| sink 处理函数 | 599 999 + 14 999 | 612 947 | 2 051 | 2 051 | 14 947 / 52 |
| no-op 处理函数 | 599 998 + 14 999 | 612 986 | 2 011 | 2 011 | 14 952 / 47 |
| 对照 | 599 999 + 14 999 | 614 998 | 0 | 0 | 14 999 / 0 |

进入模块的包都在设备计数里对上账（设备内差值 0），ACK 数等于 Inform Accepted；未 ACK 的 Inform 都是被内核丢弃、没到模块的包，发送方会重试。

### Gateway CPU 与堆

| 组 | storm 阶段 CPU 均值（占 14 核） | 约合核数 | storm 堆 MiB（最低/最高/平均） |
|---|---|---|---|
| sink 处理函数 | 23.2% | 3.2 | 335 / 1333 / 809 |
| no-op 处理函数 | 19.8% | 2.8 | 419 / 1486 / 957 |
| 对照 | 1.3% | 0.2 | 437 / 1409 / 921 |

堆没有上升趋势，GC 后回落；三组都远低于 2048 MiB 上限。

### 热点观察（未改代码）

- no-op 函数几乎不做事，但 2 045 条/s 时 Gateway 多用约 2.6 个核，折合每条 Record 约 1.3 ms CPU，开销在驱动的调用路径上。`ScriptRecordHandlerInvoker` 为了让 Script Library 保存后下一条立即生效，每条 Record 都重新执行：`getManifests()` 查项目、`getProjectScriptManager()`、`createLocalsMap()`，以及用 `runCode("__snmp_record_handler__ = <function>")` 编译并执行一段 Python 源码来解析函数，最后 `runFunction`。其中每条一次的 Jython 源码编译最可疑；Gateway 镜像内没有 jcmd/jstack，本次没有取线程采样，未定量区分各步耗时。
- 两组处理函数的轮询延迟 max 与周期间隔 max 都在约 100–200 ms，而对照组 max 只有 7 ms；内核 UDP 丢包也只在这两组出现。推测是 Record 调用路径占用 CPU 并产生大量短命对象，导致 Gateway 线程（含轮询回调和 Trap 接收）周期性停顿；这是推测，未验证。
- 可选方向（交协调者/负责人决定，本票不改）：按项目缓存已解析的函数，在项目保存或 ScriptManager 变化时失效；或减少每条的 manifest 查询。现有热更新语义（下一条使用新代码）需要保持。

### 复现

```bash
python3 system-test/storm.py run --baseline 180 --storm 300 --burst 0 --flood 0 --recovery 60 --handler 1
python3 system-test/storm.py run --baseline 180 --storm 300 --burst 0 --flood 0 --recovery 60 --handler 2
python3 system-test/storm.py run --baseline 180 --storm 300 --burst 0 --flood 0 --recovery 60
python3 system-test/storm.py cleanup
```

`--handler` 会以覆盖方式重新导入 `st-records` 夹具、恢复 normal 模式并清空 sink。每组约 10–12 分钟。sink 组结束后 sink 文件约 61 万行，测试后必须清空（`st.clear_records()`）。

本次结束后已删除全部 st-* 设备与 IP 别名，st-records 为 normal、sink 为空，Gateway ensure 与 OPC UA 连通正常，MAC 仍为 `02:42:ac:12:00:62`。
