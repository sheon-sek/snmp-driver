# Coding Agent Custom Instructions

## 默认语言与回复风格

- 默认使用中文。
- 回复直接、简洁、聚焦重点。
- 说明文档、计划、总结、验证报告默认使用中文。

## 文件操作安全规则

- 禁止递归删除、批量删除、目录删除，包括 `Remove-Item -Recurse`、`rm -rf`、`rmdir /s`。
- 删除文件时，一次只能删除一个明确绝对路径文件。
- 需要删除多个文件时，先列出具体单文件绝对路径，等待用户明确授权。
- 所有读取、修改、创建、移动、复制、删除必须使用基于 repo root 的绝对路径。

## Verification 规则

- Build、test、lint、typecheck、compile、package、format check 前，workdir 必须是 repo root。
- 修改完成后默认只给 summary。
- 未获用户明确授权，不执行任何 Verification。
- 需要 Verification 时，先询问用户。

## Context7

涉及 library、framework、SDK、API、CLI 或 cloud service 当前契约时，先运行：

```text
npx ctx7@latest library <name> <question>
npx ctx7@latest docs <library-id> <question>
```

- 必须先解析 library ID，除非用户已提供 `/org/project`。
- 每个问题最多 3 个 Context7 命令。
- Context7 请求在 sandbox 外执行。
- 配额错误时报告错误，不静默回退。
- 纯重构、业务逻辑、代码审查不需要 Context7。

--- project-doc ---

# Repository Guidelines

## Project Structure

这是 Ignition 8.3 SNMP Device Driver 项目。

- `base/`：通用 API、生命周期、License runtime。
- `common/`：SNMP Driver 模块常量。
- `gateway/`：Gateway hook、License services/routes；后续承载 Device API runtime。
- `web-ui/`：只构建 Gateway License page。

`web-ui/src/packages` 只允许保留 `page/license`。

## Project Identity

- Gradle root：`snmp-driver`
- Module ID：`com.jw.snmpdriver`
- Module name：`SNMP Driver`
- Mount alias：`snmp-driver`
- API base：`/data/snmp-driver/api/v1`
- Java package：`com.jw.snmpdriver`

## Target Architecture

SNMP runtime 应遵循：

```text
AbstractDeviceModuleHook
└── SnmpDeviceExtensionPoint
    └── SnmpDevice
```

`DeviceExtensionPoint` 负责 typed settings、设备创建、health check。`Device` 负责 OPC UA address space、subscription/polling 与生命周期。不要加入无法工作的占位实现。

## Commands

所有命令从以下 repo root 执行：

```text
~/snmp-driver   (WSL Ubuntu-24.04；Windows 主机上通过 wsl 执行)
```

```powershell
.\gradlew.bat build
.\gradlew.bat :base:test
.\gradlew.bat :gateway:test
yarn --cwd web-ui install
yarn --cwd web-ui run lint
yarn --cwd web-ui run build
```

未获明确授权，不执行上述验证命令。

## Coding Style

- Java 17。
- Java class/component 使用 PascalCase。
- method、field、hook、utility 使用 camelCase。
- module-specific Java package 使用 `com.jw.snmpdriver`。
- 保持实现轻量；没有明确需求时不增加抽象、依赖或兼容层。

## Security

- 不提交 credentials、Gateway export、signing key、`.env.local`。
- License management 使用 Ignition Gateway Web UI session access control。
- POST License routes 必须保留 CSRF access control。
- Development `.modl` 未签名，不是生产 artifact。

<!-- igdev:start -->
<!-- managed by igdev; edit outside these markers only -->
## igdev
`igdev.toml` declares this repository's Ignition toolchain; run `igdev status` first, and prefer `igdev gateway` verbs for the Gateway, and use docker or curl directly when no verb covers the need.
<!-- igdev:end -->
