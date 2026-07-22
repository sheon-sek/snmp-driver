# SNMP Driver — Feature List

## 模块简介

SNMP Driver 是面向 Ignition 8.3 的原生 Device Driver Module。它将 SNMP 设备数据接入 Ignition OPC UA，供 Tag 监控、历史记录、报警及控制流程使用。

- **Module Name：** SNMP Driver
- **Current Version：** 0.1.0-beta7
- **Platform：** Ignition 8.3

## 核心功能

- 原生集成 Ignition Device Connection 与 OPC UA 地址空间
- 支持 SNMP v1、v2c、v3
- 支持 UDP、IPv4、IPv6 及自动 IP Family 选择
- 支持 SNMP GET 轮询与 SNMP SET 写入
- 提供 Read Only 与 Read/Write 设备访问模式
- 支持 SNMPv3 `noAuthNoPriv`、`authNoPriv`、`authPriv`
- 支持 MD5、SHA-1、SHA-2 身份验证协议
- 支持 DES、AES-128、AES-192、AES-256 隐私协议
- 使用 Ignition Secret Management 保护 community 与 SNMPv3 credentials
- 通过 numeric OID Item Path 创建 OPC Tags；支持显式 SNMP 数据类型
- 根据 OPC UA subscription 自动启动、调整及停止 polling
- 合并相同 OID polling，并按 OID 数量及请求大小自动分批
- 可配置 timeout、retry、每次请求 OID 数量及设备并发请求上限
- 内置 circuit breaker；通信异常时保留最后可用值并更新 OPC UA quality
- 提供设备状态、活动 subscription/OID 数量、poll interval、最近成功及失败信息
- 将配置、通信、协议、安全及 License 问题映射为明确 OPC UA status code
- SNMP 操作仅在有效 Module License 下运行
- 提供 Gateway License 页面，支持 License 查看、上传及重新验证

## 当前支持范围

- 通信模式：SNMP polling、GET、SET
- OID 格式：numeric dotted notation
- 最低 polling interval：1 秒
- 当前不包含：Trap/Inform、MIB 文件导入、MIB 名称解析、redundancy coordination
