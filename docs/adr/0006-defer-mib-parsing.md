---
status: accepted
---

# 推迟 MIB 解析，未来首选离线字典

B1 不实现 MIB 解析，Notification 与 Tag 以原始数字 OID 展示。未来方向首选离线字典：用 snmptranslate/smidump/pysmi 将 MIB 转为 JSON，模块只负责加载，运行时无新依赖、无许可风险；需要直接上传 .mib 时再评估商业授权的 SNMP4J-SMI-PRO。Mibble 为 GPL（商业版另购），不适用于闭源模块。
