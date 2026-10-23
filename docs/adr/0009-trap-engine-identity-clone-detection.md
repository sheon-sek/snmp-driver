---
status: accepted
---

# Trap 本地引擎身份：按机器指纹检测克隆，恢复备份只做 boots+1

v3 Inform 以 Gateway 为权威方，本地 engineID 与 engineBoots 存在 `$data/config/local/com.jw.snmpdriver/trap-engine.properties`。该目录随备份一起恢复，也会随 Gateway 克隆被复制：恢复旧备份可能让 boots 回退，克隆会让两台 Gateway 共用一个 engineID。

决定：

- 身份文件额外记录 `machineFingerprint`，内容为 License 所用机器标识（`MachineInfoService.hashedMachineInfo`）的哈希，逗号分隔，不存明文。启动时按 License 的比例匹配规则比对：达到阈值视为同一台机器，boots+1；未达到视为克隆或换机，重新生成 engineID、boots 从 1 开始，旧文件改名为 `.cloned-<ts>` 保留，记录 warn 日志并写 `Traps/LastError`。采集不到任何标识时不判定克隆，保留现有 engineID，只记 warn。
- 恢复备份导致的 boots 回退不做猜测性补偿，只做 boots+1；手册与 `LastError` 说明发送方报 `notInTimeWindow` 时的处置（发送方重新 discovery 或清除其引擎缓存）。不自动清时间窗缓存绕过防重放。
- 指纹由模块自动管理，用户只看 `Traps/LocalEngineId`。手册给出两种人工操作：误判克隆时从 `.cloned-<ts>` 恢复文件后重启模块；主动更换 engineID 时删除文件后重启模块。

考虑过的替代方案：不检测、手册要求克隆后手动删文件（容易遗漏，两台 Gateway 静默共用身份）；每次启动生成新 engineID（违背 RFC 3414，发送方每次重启都要重配）；用时间戳猜测备份回退并一次性大幅增加 boots（判不准）；回退时停用 v3 Inform 等待人工确认（静默失效）。

后果：Docker 重建容器可能改变机器标识并触发重新生成 engineID，手册建议固定 hostname 与 MAC。误判的最坏结果是多生成一次 engineID，只影响 v3 Inform，v1/v2c/v3 Trap 不受影响。
