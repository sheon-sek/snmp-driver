# Coding Agent Custom Instructions

## 默认语言与回复风格

- 默认使用中文回复。
- 默认使用中文编写说明文档、总结、开发计划、验证报告等内容。
- 回复应直接、简洁、聚焦重点。
- 不需要额外寒暄、客套或字面礼貌修饰。
- 不要重复解释已明确的要求。

---

## 文件操作安全规则

### 严禁批量删除文件或目录

禁止使用任何递归删除、批量删除、目录删除命令，包括但不限于：

```powershell
del /s
rd /s
rmdir /s
Remove-Item -Recurse
```

```bash
rm -rf
```

### 删除文件的唯一允许方式

需要删除文件时，只能一次删除一个**明确的绝对路径文件**。

正确示例：

```powershell
Remove-Item "C:\path\to\file.txt"
```

### 批量删除处理规则

如果任务需要批量删除文件、清理目录、删除生成物或删除多个匹配文件：

1. 立即停止删除操作。
2. 不要自行执行批量删除。
3. 向用户说明需要删除哪些内容。
4. 请求用户手动删除，或等待用户明确授权具体的单文件删除路径。

---

## 文件路径规则

- 所有文件操作必须使用绝对路径。
- 绝对路径必须以当前 repo 的 root/workdir 为基础。
- 不允许使用模糊路径、相对路径或依赖当前 Shell 状态的路径。
- 修改、读取、创建、移动、复制、删除文件时都必须遵守此规则。

示例：

```text
正确：
C:\repo\src\Module\File.cs

避免：
src\Module\File.cs
.\src\Module\File.cs
```

---

## Build / Test / Lint 执行规则

每次执行 build、test、lint、typecheck、format check 或类似验证命令前，必须先确保 Shell 当前工作目录是当前 repo root。

执行前应显式设置 workdir，例如：

```powershell
Set-Location "C:\path\to\repo"
```

或在工具调用中直接指定 repo root 作为 `workdir`。

目的：避免 Shell 实际所在目录错误，导致 build/test 执行失败或验证结果不准确。

---

## 修改后的默认流程

完成代码或文档修改后：

1. 直接给出简洁 summary。
2. 不要自动执行 build/test/lint，除非用户明确要求。
3. Summary 后询问用户是否需要协助进行 Verification。

推荐格式：

```markdown
## Summary

- 修改了 xxx
- 修正了 xxx
- 补充了 xxx

## Skills Used

- xxx
- xxx

是否需要我继续帮你进行 Verification，例如 build、lint 或 test？
```

---

## Verification 规则

默认情况下：

- 修改完成后不主动执行 build/test/lint。
- 即使原始任务没有说明是否需要验证，也不要自行验证。
- 应先询问用户是否需要继续执行 Verification。
- 用户明确要求后，才执行对应验证命令。

Verification 包括但不限于：

- build
- lint
- test
- typecheck
- format check
- compile
- package
- integration test

---

## 技能调用总结规则

每次执行完成后的总结中，可以用最简洁方式说明本次过程中调用了哪些技能。

示例：

```markdown
## Skills Used

- planner
- executor
- validator
```

如果没有调用任何技能，可以省略该 section，或写：

```markdown
## Skills Used

- none
```

---

## 冲突处理规则

如发现用户请求与以上安全规则冲突，优先遵守安全规则，并简洁说明原因。

---

## 强制优先级

以下规则属于硬性要求，必须优先遵守：

1. 禁止递归/批量删除。
2. 文件操作必须使用绝对路径。
3. build/test/lint 前必须确认当前 workdir 是 repo root。
4. 修改完成后默认只 summary，不主动 verification。
5. 需要 verification 时必须先询问用户。

---

<!-- context7 -->
Use the `ctx7` CLI to fetch current documentation whenever the user asks about a library, framework, SDK, API, CLI tool, or cloud service -- even well-known ones like React, Next.js, Prisma, Express, Tailwind, Django, or Spring Boot. This includes API syntax, configuration, version migration, library-specific debugging, setup instructions, and CLI tool usage. Use even when you think you know the answer -- your training data may not reflect recent changes. Prefer this over web search for library docs.

Do not use for: refactoring, writing scripts from scratch, debugging business logic, code review, or general programming concepts.

## Steps

1. Resolve library: `npx ctx7@latest library <name> "<user's question>"` — use the official library name with proper punctuation (e.g., "Next.js" not "nextjs", "Customer.io" not "customerio", "Three.js" not "threejs")
2. Pick the best match (ID format: `/org/project`) by: exact name match, description relevance, code snippet count, source reputation (High/Medium preferred), and benchmark score (higher is better). If results don't look right, try alternate names or queries (e.g., "next.js" not "nextjs", or rephrase the question)
3. Fetch docs: `npx ctx7@latest docs <libraryId> "<user's question>"`
4. Answer using the fetched documentation

You MUST call `library` first to get a valid ID unless the user provides one directly in `/org/project` format. Use the user's full question as the query -- specific and detailed queries return better results than vague single words. Do not run more than 3 commands per question. Do not include sensitive information (API keys, passwords, credentials) in queries.

For version-specific docs, use `/org/project/version` from the `library` output (e.g., `/vercel/next.js/v14.3.0`).

If a command fails with a quota error, inform the user and suggest `npx ctx7@latest login` or setting `CONTEXT7_API_KEY` env var for higher limits. Do not silently fall back to training data.
Run Context7 CLI requests outside Codex's default sandbox. If a Context7 CLI command fails with DNS or network errors such as ENOTFOUND, host resolution failures, or fetch failed, rerun it outside the sandbox instead of retrying inside the sandbox.
<!-- context7 -->

--- project-doc ---

# Repository Guidelines

## Project Structure & Module Organization

This is an Ignition 8.3 module boilerplate with five Gradle scopes.
`base/` contains reusable lifecycle, API, and auth infrastructure. `common/`
contains module constants and the Perspective component descriptor. `gateway/`
contains module services, routes, navigation, and Perspective registration.
`designer/` registers the Perspective example component. `web-ui/` contains
React 18 + TypeScript + Vite targets for webapp, widget, page, and Perspective.

## Build, Test, and Development Commands

- `.\gradlew.bat build`: builds all module scopes and creates the `.modl`.
- `.\gradlew.bat :base:test`: runs shared auth/lifecycle JUnit 5 tests.
- `.\gradlew.bat :gateway:test`: runs Gateway JUnit 5 tests.
- `yarn --cwd web-ui install`: installs frontend dependencies.
- `yarn --cwd web-ui run dev`: starts Vite on `http://localhost:9999`.
- `yarn --cwd web-ui run lint`: runs TypeScript validation.
- `yarn --cwd web-ui run build`: builds all embedded frontend targets.

All commands must run with repository root as the current workdir.
Do not run build, test, lint, typecheck, compile, package, or similar
verification without explicit user authorization.

## Coding Style & Naming Conventions

Java targets JDK 17. Module-specific packages currently use
`com.jw.ignitionsdkboilerplate`; reusable `base` packages use `com.jw.ignition`.
Use PascalCase for Java classes and React components, camelCase for methods,
fields, hooks, and utilities. Keep the boilerplate minimal; add dependencies
and abstractions only when an example requires them.

## Testing Guidelines

Backend tests use JUnit Jupiter under `base/src/test/java` and
`gateway/src/test/java`. Use `*Test.java` names. Baseline coverage includes
shared auth/lifecycle behavior and the Gateway health payload. Frontend
verification uses TypeScript checks unless a test runner is explicitly added.

## Security & Configuration Tips

Do not commit credentials, Gateway exports, signing keys, or local `.env.local`
files. `web-ui/.env.dev` is the tracked Vite development configuration.
Development builds are unsigned and are not production artifacts. Embedded API
routes currently use `/data/ignition-sdk-boilerplate/api/v1`.

## Workdir

Always use
`D:\EETARP\01_Projects\_IgnitionEngineeringTools\tutorials\ignition-sdk-boilerplate`
as repository root and use absolute paths for all file operations.
