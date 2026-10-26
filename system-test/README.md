# M1d 系统测试

net-snmp 与 igdev Gateway 的互通测试（计划 §6.3，#41）。升级、恢复、克隆测试（#42）和风暴测试（#43）复用这里的框架。

## 组成

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 测试容器 `snmp-systest`：net-snmp（`snmptrap`、`snmpinform`、`snmpd`）+ Python `asyncua` |
| `opc.py` | 在容器内运行的 OPC UA 客户端：`browse`、`read`、`poll`（订阅即触发 SNMP 轮询） |
| `st.py` | 在 WSL/Linux 主机上运行的驱动：环境准备、设备 CRUD（Gateway REST）、断言工具、运行器 |
| `cases.py` | 测试用例，`@case("<group>:<name>")` |
| `snmpd.conf` | 轮询检查用的测试 agent（community `st-public`） |

测试容器接入 igdev 的 Docker 网络，直接把包发到 Gateway 容器的 10162 端口（Q30），主机上不需要安装 net-snmp。断言只读取设备已有的 OPC UA 节点 `[Device]/Traps/*`、`Diagnostics/*` 和 SNMP Item，不为测试新增模块 API。

## 前置条件

- 在 repo root 下，`igdev gateway ensure` 已成功，SNMP Driver 模块已加载且已授权。没有 License 时，设备会显示 `Unlicensed`，除 License 相关项以外的用例都会失败。
- 主机已安装 Python 3 和 Docker。

## 运行

```bash
python3 system-test/st.py setup            # 一次：见下
python3 system-test/st.py list
python3 system-test/st.py run              # 全部（约 12 min）
python3 system-test/st.py run v2c v3:AUTH_PRIV:SHA256:AES128   # 按组或按名字
```

`setup` 是幂等的，会做以下几件事：

1. 在 `.igdev/runtime/compose.yaml` 的 gateway 服务里固定 `hostname` 与 `mac_address`，然后重建容器（数据卷保留）。Docker 每次启动容器都会分配随机 MAC，不固定的话，每次重启都会被判定为克隆（ADR 0009 补记）。`igdev setup` 会重新生成这个文件，重跑 `st.py setup` 即可恢复固定。
2. 把 Gateway OPC UA Server 改为绑定 `0.0.0.0`（只在容器网络内可达，没有发布到主机），认证源改为 igdev admin 所在的 user source（`IGDEV_SYSTEM_USER_SOURCE`，默认 `temp`）。安全策略保持 Basic256Sha256 + SignAndEncrypt。
3. 构建并启动 `snmp-systest`，把它的 OPC UA 客户端证书（`system-test/.pki/`，已被 git 忽略）上传为 Gateway 信任证书。
4. 在容器中启动 `snmpd`。

每个用例开始前会删除所有 `st-*` 设备。结果写到 `system-test/out/results-<ts>.json`（已被 git 忽略）。

## 凭据

admin 口令在运行时从 `igdev gateway credentials` 读取，通过环境变量传给容器，不会写入磁盘。用例里的 community 和口令都是测试常量。仓库里不提交 Gateway 导出、证书或 License（Q38）。

## 会改变 Gateway 的用例

- `restart:*`、`module:*`：会重启 Gateway。
- `docker:udp162-mapping-source-ip`：临时给 compose 加上 `162:10162/udp` 并重建容器，结束时在 `finally` 中撤回。需要主机 UDP 162 没有被占用。
- `v3:inform-replay-stale`：要等 160 s 让报文超出时间窗。
