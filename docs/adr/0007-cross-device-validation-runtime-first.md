---
status: accepted
---

# 跨设备冲突以运行期复查为准，保存期拒绝不作为 M1a 门槛

`DeviceExtensionPoint.validate(settings, errors)` 拿不到当前资源身份，也看不到禁用设备；`ServiceBundle` 只保存运行期 Device，不是完整配置集合。直接用 `ConfigurationManager.getResources` 扫描会把编辑前的自己当成冲突，并可能递归触发 decode→validate，批量导入与并发保存还会读到过期快照。因此 M1a 把单设备校验放在 `validate`，把同端口 bindAddress 不一致、同名 USM securityName 配置不一致等跨设备冲突交给 `registry.acquire` 的运行期复查：冲突的 Device 进入 Trap Status `Degraded`，`Traps/LastError` 写明冲突对象，轮询不受影响。保存期跨设备拒绝改为独立 spike，在 igdev Gateway 上验证资源枚举入口；可行则在 M1a 之后接入，不可行则保持运行期降级，并在手册说明同端口谁先启动谁生效。

此前 `SNMP_DRIVER_V2_PLAN.md` §4.8 写明入口未解决前不宣布 M1a 可实施，本决策撤销该前置条件。

## Spike #20 结论（2026-10-22，igdev Gateway 8.3.10 实测）

保存期跨设备拒绝在技术上能枚举资源，但不适合放进 `validate`，决定维持运行期复查，不接入保存期拒绝。

- `GatewayContext.getConfigurationManager().getResources(DEVICE_RESOURCE_TYPE)` 在 `validate` 内可用，返回原始 `Resource`（不经 codec，不递归），含禁用资源（`enabled=false` 属性），耗时约 0 ms。
- `validate` 不只在保存时执行：列表接口（`NamedTypeRoutes` → `ResourceTypeRoutes.resourceToInfo` → `ExtensionPointResourceCodec.decodeFromJson`）、启动加载、保存前与保存后（`platform-executor`）都会调用。若在 `validate` 报跨设备冲突，已存在的冲突资源会使列表与启动解码失败。
- 保存前的快照不含正在保存的资源；编辑已有设备时快照里是旧版本，`validate` 拿不到资源名，无法排除自己。例如只有一台设备把 bindAddress 从 X 改为 Y，会被旧版本的自己误报冲突。
- 一次批量保存里的同批其他资源在 `validate` 时不可见（POST B、C 时两者都只看到已有的 5 个），同批冲突无法在保存期发现。
- 启动时（`ServiceBundle` 尚未运行）`services.context()` 会抛 `IllegalStateException`，需要另行取得 `GatewayContext`。

因此冲突仍由 `registry.acquire` 运行期复查并降级。手册需说明同端口谁先启动谁生效。若将来需要保存期拒绝，应找能拿到资源名且只在保存时触发的入口，而不是 `validate`。
