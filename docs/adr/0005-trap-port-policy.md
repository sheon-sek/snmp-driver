---
status: accepted
---

# Trap 端口默认 162，绑定失败只降级 Trap 功能

`trapPort` 可配置、默认 162，以匹配多数设备的默认目标端口。绑定失败时只有 Trap 功能进入 Degraded，轮询不受影响，`Traps/LastError` 给出处置建议，并在仍有引用时周期性重试绑定。Linux 首推模块监听 10162、由 nftables 将 162 重定向过去，不推荐对 JVM `setcap`（权限作用于整个 JVM 且随 JRE 升级失效）；Windows 需停用系统 SNMP Trap 服务；Docker 映射 `162:10162/udp`。同一端口上的 Device 共享一个 Trap Listener，bindAddress 必须一致。
