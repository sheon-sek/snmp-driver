# M1d-3 (#42) 升级、备份恢复与克隆测试报告

日期：2026-10-26。分支：`m1d/release`。环境与 #41 相同（见 `M1D_41_SYSTEM_TEST.md`），Gateway 的 hostname/MAC 已固定。步骤脚本：`system-test/upgrade.py`；每步的原始证据在 `system-test/out/`（git 忽略）。

## 结论

全部验收项通过。Q12 的硬性退出条件满足：1.0.0 的设备配置在 1.1.0 下不用修改就能加载，`trapEnabled=false`，轮询结果与升级前一致。恢复和克隆时本地引擎身份的行为与 ADR 0009 一致。本票没有发现模块缺陷；发现 1 个 igdev 工具问题（见文末），不影响结论。

## 备份与恢复

- 测试前对主 Gateway 做了完整备份（`.scratch/backups/pre-42.gwbk`，不入库），测试结束用它恢复（`restoreLocal=false`，保持 engineBoots 单调递增）。恢复后冒烟用例通过。
- 用户已授权本票内对测试 Gateway 做 `.gwbk` 恢复覆盖（Q36）。没有执行 `igdev gateway reset`。

## 升级 1.0.0 → 1.1.0（Q12）

1.0.0 构建自提交 `6159362`（`moduleVersion=1.0.0`）。测试用的 1.0.0 和 1.1.0 构建都打了 developer-mode License bypass 补丁（本机没有签发 License；补丁只改 License runtime，不入库）。

| 步骤 | 结果 |
|---|---|
| 装 1.0.0，按 1.0.0 的 19 个字段（没有 TRAP 分组）通过 REST 建 `up-v2c`（V2C）和 `up-v3`（V3 authPriv SHA256/AES128，口令是 Embedded Secret） | 轮询 sysName/sysLocation 成功；`Diagnostics/Status=Idle` |
| 导出 1.0.0 时期的 `.gwbk` | `v100-devices.gwbk` |
| **原地升级**到 1.1.0 | 两台设备不用修改就加载；轮询值逐项一致（包括 v3 Secret 解码）；`Traps/Status=Disabled`；REST 读出的 TRAP 字段都是默认值（`trapEnabled=false`、`trapPort=162` 等）；磁盘上 1.0.0 的 `config.json` 没有被改写（修改时间仍是 1.0.0 时的创建时间，没有 trap 字段） |
| **导入路径**：在运行 1.1.0 的 Gateway 上恢复 1.0.0 时期的 `.gwbk` | 与原地升级的结果相同；与 1.0.0 快照相比，唯一的差异是新增 TRAP 字段的默认值 |
| 启动后 Status | 两台设备都是 `Disabled`（Trap 关闭），设备 `Diagnostics/Status` 正常。注意：在 bypass 构建下 License 始终有效，所以 #40 修复的“启动时短暂显示 Unlicensed”在这个环境里观察不到，由 #40 的单元测试覆盖 |

## 备份恢复与 engineBoots（Q32，ADR 0009）

同一台 Gateway，v3 设备 `st-v3` 在备份时已经存在。

| 场景 | 前 → 后 | 发送方 |
|---|---|---|
| 恢复 local（`restoreLocal=true`），备份时 boots=4，恢复前 boots=6 | engineID 不变；boots 6 → **5**（回退） | 重放一份在 boots=6 时抓的 Inform：Gateway 回了 Report（notInTimeWindow），`AuthFailureCount` +1，`Accepted` 0，`LastError`=“v3 Inform 超出 Gateway 本地时间窗（engineBoots/engineTime 不一致），已按防重放拒绝；发送方需重新 discovery 或清除其引擎缓存。”新进程重新 discovery 后的 Inform 正常 ACK |
| 不恢复 local（`restoreLocal=false`），恢复前 boots=5 | engineID 不变；boots 5 → 6（递增） | Inform 正常，没有报错 |
| 导入 1.0.0 备份（`restoreLocal=false`） | boots 2 → 3 | — |

处置方法写入了手册变更清单 §10：同一台机器上做日常恢复时不恢复 local；如果恢复了 local，发送方重新 discovery 或清除引擎缓存即可。模块没有自动清除时间窗缓存。

风险说明（ADR 0009 已接受）：恢复 local 之后，boots 会回到一个之前用过的值，理论上在此期间截获的报文可以在时间窗内重放。这是 RFC 3414 下 boots 回退的固有问题，所以建议日常恢复不恢复 local。

## 克隆

按用户选择，用第二个 igdev 实例做真实克隆：在 git worktree `.scratch/clone` 里起了独立的 Gateway `igdev-154b0811`（MAC 未固定），把主 Gateway 的 `.gwbk` 带 local 恢复上去。

| 项目 | 结果 |
|---|---|
| 克隆机首次启动时自己的身份 | `…5e097261`，boots 1 |
| 恢复后 | 恢复进来的主机身份（`…24dc7d70`，boots 4，主机指纹）被判定为克隆，改名为 `trap-engine.properties.cloned-20261026T075100Z` 保留；生成新的 engineID `…3218cb25`，boots 1 |
| 克隆机上 `st-v3` 设备 | `Traps/LocalEngineId=8000137001ac1300023218cb25`；`LastError`=“检测到 Gateway 被克隆或更换机器，已生成新的 Trap 本地 engineID，旧文件备份为 trap-engine.properties.cloned-20261026T075100Z；请在 v3 Inform 发送方重新配置 engineID。若为误判，可从备份恢复文件后重启模块。” |
| 主 Gateway | engineID 不变，两台 Gateway 不共用身份 |

测试结束后，克隆实例已用 `igdev gateway down --volumes` 关闭并删除数据卷。worktree 目录 `.scratch/clone` 还在，删除目录需要用户授权。

## igdev 问题（不属于模块）

igdev 0.3.1 `module install` 在新模块的文件名和已暂存的不同（版本号变化）时，会先删掉旧的暂存文件，再执行 `docker compose restart`。容器的 bind mount 仍然指向旧文件，Docker 就在宿主机上建一个同名的 root 空目录，然后启动失败（`not a directory`）。绕过办法：按 igdev 新生成的 compose 重建容器（`st.py` 的 `recreate_gateway`）。之后再安装同名文件时，igdev 会被这个空目录挡住，所以 1.1.0 构建改用文件名 `…-beta4-dev.unsigned.modl` 安装。留下的空目录：`.igdev/modules/com.jw.snmpdriver-1.0.0.unsigned.modl/`、`.igdev/modules/com.jw.snmpdriver-1.1.0-beta4.unsigned.modl/`，删除需要用户授权（root 拥有）。
