# #53 License PENDING 暂存 Trap

日期：2026-10-28。分支：`feat/53`；实现工作目录：`/home/sheon/wt/snmp-53`。

## 实现与边界

PENDING Trap 复用每 listener 的原有 10 000 条有界接收队列，不新增暂存集合。
认证、路由、格式检查与 Received 计数发生在入暂存队列前。
worker 在队列头等待 License 判定（有待判定数据时至多每 50 ms 检查一次；空队列使用通知唤醒），不先取出额外一条占用无界的等待位置。
LICENSED 后，原有 Trap 和在暂存排空前新到的 Trap 按 FIFO 进入限速、快照、Record Delivery；
无效、过期及验证失败则逐条计 DroppedUnlicensed，不产生快照或 Record。
队列满丢新到的，计 DroppedQueueFull，LastError 提示队列已满。
队列及暂存标记使用同一短临界区，Device sink 和 LastError 回调不在队列锁内执行。

Inform 在 PENDING 下不 ACK、不暂存、不写去重缓存，新增设备 UInt64 节点 `Traps/PendingInformCount`
累计此类接收尝试（每次重发分别计数）。有效判定后的重发遵循 ADR 0004：去重查询→限速→入队→去重写入→ACK，
不等待项目函数；Q5 的已判定无效行为保持不变。
PENDING 不改变 #40 的 Listening 状态。暂存只在内存中，停止/配置重建沿用现有接收队列生命周期。

## TDD 与单元测试

测试边界已由协调器确认：SNMPDeviceFixture 的真实 UDP listener、Device OPC UA 节点及 Record Handler 公共边界。
仅外部 Gateway 接口、License 状态和项目函数使用夹具控制，不测试私有 Router 方法。

红→绿证据：

1. `pendingTrapsWaitThenProduceRecordsInArrivalOrderWhenLicensed` 初始失败：
   PENDING Trap 被错误计入 DroppedUnlicensed；实现暂存后通过。
2. `pendingInformIsNotAcknowledgedOrStoredAndRetryIsAcceptedOnlyAfterLicenseVerdict` 初始失败：
   PENDING Inform 被错误计入 DroppedUnlicensed；增加 PendingInformCount 后通过。
3. 补充真实队列容量、无效判定与对账回归；容量测试每包间隔 1 ms，避免将 UDP socket 突发丢包误判为驱动队列行为。

新增测试类 `SNMPDevicePendingLicenseTest`：

| 测试 | 观察结果 |
|---|---|
| pendingTrapsWaitThenProduceRecordsInArrivalOrderWhenLicensed | 3 条暂存，判有效后追加第 4 条，payload 与 Record sequence 顺序 1..4，每条一次 |
| pendingInformIsNotAcknowledgedOrStoredAndRetryIsAcceptedOnlyAfterLicenseVerdict | 同一源端口/request ID：PENDING 首次不 ACK；有效后重发入队并 ACK；再重发只计 DuplicateInform，Record 只有一条 |
| invalidLicenseDiscardsAllStagedTrapsWithoutProducingRecords | MISSING_FILE、EXPIRED、IO_ERROR 三种判定分别丢弃 3 条；恢复 License 不重放旧数据 |
| pendingTrapsShareTheExistingBoundedQueueAndOverflowCountsOnlyNewArrivals | 10 001 条 Received，保留前 10 000 条，第 10 001 条计 QueueFull；判无效后 10 000 条计 Unlicensed；Listening 不变且有队列满提示 |

在 worktree 根目录运行 `bash ./gradlew :base:test :gateway:test --console=plain`，2m58s，BUILD SUCCESSFUL。
JUnit XML 汇总：base 13 项、gateway 340 项；failure/error/skipped 全部为 0。
其中本票 6 个测试实例（4 个测试方法，无效判定参数化为 3 种状态）全部通过。
已运行 `git merge m1d/release`，结果 Already up to date；验证基线为 `48c2d71c9566bb91fb1ad3ca82a9490553805a90`。
`python3 -m py_compile` 检查 `system-test/cases.py`、`system-test/st.py` 成功。

## 对账口径

Received 统计认证通过且格式有效的**每次尝试**，不等于唯一 Notification 数。
启用 Inform 接收、v3 引擎身份正常且接收队列排空后：

```text
Received = Accepted + DroppedRateLimit + DroppedQueueFull + DroppedUnlicensed
         + DuplicateInform + PendingInform
```

暂存或尚未交付时，右侧另加暂存/接收队列中及 worker 交付中的条数。
RecordQueueDepth 属于接受后的独立 Record 投递层，不是 PENDING 暂存深度。

本票的确定性样例：

- 放行：4 = 4 Accepted，Unlicensed=0，RecordDelivered=4。
- 无效：3 = 3 DroppedUnlicensed；恢复后 4 = 1 Accepted + 3 DroppedUnlicensed。
- 满：10 001 = 10 000 DroppedUnlicensed + 1 DroppedQueueFull。
- Inform：3 Received = 1 Accepted + 1 PendingInform + 1 DuplicateInform，RecordDelivered=1。

认证失败不计 Received。禁用 Inform 接收、v3 身份异常另有原有的未 ACK Received 分支，不能直接套用此排空等式。
停止/重配置期间队列取消沿用旧行为，不是持久化交付保证。

## 系统用例与未覆盖项

新增 `restart:license-pending-traps`：重启后 listener 首次显示 Listening 即发 10 条 Trap，
检查 Accepted、Record、序号及全部丢弃计数。
用例返回是否观察到 PENDING 暂存提示；未观察到时明确仅为重启 smoke。
生产 License 验证无可控延迟配置，bypass 可能直接返回 LICENSED，不能仅凭此用例通过就宣称实际制造了 PENDING；
确定性判定转移由上述 JUnit 覆盖。

按任务约束，本 implementer 未使用 igdev Gateway、未安装模块、未执行定向或全量系统回归。
merger 需要串行运行新增用例及全量系统测试，并记录真实 PENDING 是否可观察；
本报告不宣称系统验收完成。
v1/v3 各安全级别的 PENDING 专项未单独运行，共享暂存逻辑由 v2c Fixture 覆盖，现有协议单元回归覆盖正常安全与路由路径。

## 集成验收（merger）

日期：2026-10-28。环境：WSL Ubuntu-24.04、Ignition 8.3.10、igdev Gateway。

### 合并与单元测试

- 集成分支 `m1d/release`：`git merge --no-ff feat/53`（`abd6617`），合并提交 `d631284`，无冲突。
- repo root 执行 `bash ./gradlew :base:test :gateway:test --console=plain`：BUILD SUCCESSFUL（2 分 41 秒），JUnit XML 合计 base 13、gateway 340，失败/错误/跳过均为 0。
- 单测在 `gateway/.jython_cache/` 生成的缓存已移入忽略的 `.scratch`；按负责人同意在 `.gitignore` 增加该路径。

### 开发构建

- 当前源码复制到忽略的 `.scratch/devbuild-53`，只在副本应用 `.scratch/license-bypass.patch`，安装文件名 `com.jw.snmpdriver-1.1.0-m53-dev.unsigned.modl`（未签名，仅开发测试）。
- 为制造可观察的 PENDING，副本中另加一个仅开发用的判定延迟：Gateway 数据目录存在 `snmpdriver-dev-pending-ms` 文件时，License 服务创建后该毫秒数内 `currentStatus()` 返回 `UNVERIFIED`；文件不存在时与普通 bypass 构建相同。该改动只在 `.scratch` 副本中，正式源码与提交不含 bypass 或延迟。
- igdev 更换模块文件名时出现已知旧 mount 错误，用 `st.recreate_gateway` 保留数据卷重建容器，`st.pin_gateway_identity` 固定 MAC `02:42:ac:12:00:62`；模块 ACTIVE 后开始测试。

### 系统测试

| 运行 | 结果 | 结果文件（忽略的运行产物） |
|---|---|---|
| `restart:license-pending-traps`，延迟文件 30000 ms | 1/1 通过，68.4 s，**观察到 PENDING 暂存提示** | `system-test/out/results-20261028T115516.json` |
| `st.py run record`（全部 `record:*`） | 11/11 通过，137.2 s | `system-test/out/results-20261028T115744.json` |
| `st.py run` 全量（无延迟文件） | 61/61 通过，用例耗时合计 1014.0 s | `system-test/out/results-20261028T121438.json` |

真实 PENDING 时间线（Gateway 日志）：03:54:40 License 服务创建并进入 30 s UNVERIFIED；03:54:48 Device 启动、listener Listening 后立即发 10 条 Trap，`LastError` 含“License 验证中”；03:55:10 判定 LICENSED 后暂存 Trap 按序放行。
结果：Received=Accepted=RecordDelivered=10，Record 序号 1..10 有序，DroppedUnlicensed、DroppedQueueFull、DroppedRateLimit、PendingInform 均为 0。

全量中同一用例在无延迟文件时返回“PENDING window not observable”，即 bypass 直接 LICENSED 的重启 smoke，同样 10 条全部接受、零丢弃。

其他关键数据：1000 条 Trap → 1000 次调用有序；容量 100 + 慢函数 Accepted=150、Delivered=101、Dropped=49（保留 1–101），对账成立；热更新首次 v2 为导入开始后 2.173 s（record 集）/ 2.758 s（全量），之后无回退；Gateway 重启 engineBoots 2→3、engineID 保持；端口占用解除后 23 s 恢复。

系统层未单独发 PENDING 期间的 Inform 与无效判定（需要可控的无效 License），这两种转移由 `SNMPDevicePendingLicenseTest` 覆盖。

### 集成问题与清理

未发现集成问题，除 `.gitignore` 与本报告外没有新增运行时修改。
测试后删除全部测试设备（设备列表 `[]`）、`st-records` 恢复 normal 处理函数、sink 清空（`[]`）、延迟文件已删除；`igdev gateway ensure` 成功，OPC UA 可访问，MAC `02:42:ac:12:00:62`。
本验收只证明开发 bypass 构建下的功能回归，不作为正式 License 或生产签名 artifact 验收。
