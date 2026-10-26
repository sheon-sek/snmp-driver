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
