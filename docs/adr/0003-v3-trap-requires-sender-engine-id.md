---
status: accepted
---

# v3 Trap 强制配置发送方 engineID

接收 v3 Trap 的 Device 必须配置 `trapSenderEngineId`，USM 用户按该 engineID 预先本地化；不注册可按任意 engineID 现场本地化的用户。这防止伪造 engineID 造成的 CPU 与内存消耗，代价是设备 engineID 变化（换板、复位）后需重新配置。v3 Inform 不受影响，它以 Gateway 本地 engineID 为权威。
