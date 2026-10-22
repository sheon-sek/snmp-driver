---
status: accepted
---

# 跨设备冲突以运行期复查为准，保存期拒绝不作为 M1a 门槛

`DeviceExtensionPoint.validate(settings, errors)` 拿不到当前资源身份，也看不到禁用设备；`ServiceBundle` 只保存运行期 Device，不是完整配置集合。直接用 `ConfigurationManager.getResources` 扫描会把编辑前的自己当成冲突，并可能递归触发 decode→validate，批量导入与并发保存还会读到过期快照。因此 M1a 把单设备校验放在 `validate`，把同端口 bindAddress 不一致、同名 USM securityName 配置不一致等跨设备冲突交给 `registry.acquire` 的运行期复查：冲突的 Device 进入 Trap Status `Degraded`，`Traps/LastError` 写明冲突对象，轮询不受影响。保存期跨设备拒绝改为独立 spike，在 igdev Gateway 上验证资源枚举入口；可行则在 M1a 之后接入，不可行则保持运行期降级，并在手册说明同端口谁先启动谁生效。

此前 `SNMP_DRIVER_V2_PLAN.md` §4.8 写明入口未解决前不宣布 M1a 可实施，本决策撤销该前置条件。
