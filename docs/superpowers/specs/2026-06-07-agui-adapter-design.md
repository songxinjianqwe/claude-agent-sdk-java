# AG-UI 适配层 + 最小 CopilotKit 前端 — 设计文档

- 日期：2026-06-07
- 所在 repo：`claude-agent-sdk-java`（独立 repo，非 my-ai-playground 子模块）
- 状态：设计已与用户对齐，待 review → writing-plans

## 1. 背景与目标

`claude-agent-sdk-java` 是 Node `@anthropic-ai/claude-agent-sdk` 的 Java 复刻，驱动本机
`claude` CLI 走 stream-json NDJSON 协议，已把 CLI 输出解析成结构化 `SdkMessage` 流（`Flow.Publisher`
背压、token 级 partial delta、tool_use/tool_result 生命周期分离）。

**目标**：在该库上新增一个 **AG-UI 协议适配层**，把 `SdkMessage` 流翻译成 AG-UI 事件流，
通过 HTTP+SSE 暴露，让 **CopilotKit React 前端**直接连上，在浏览器里看到 Claude 的
**文本打字机 + 工具调用过程 + 思考占位 + 多轮对话**。

AG-UI 是 CopilotKit 发起的「Agent↔前端」开放协议（事件驱动、SSE 传输、~16 种标准事件）。
本设计把本库变成一个 AG-UI 兼容的 agent 后端。

## 2. 范围

### 做
- 文本流式（打字机，token 级）
- 工具调用过程（开始 / 入参 / 结束 / 结果）
- 思考占位（reasoning 事件；内容受限见 §7）
- 多轮对话（用 SDK 的 `.resume(sessionId)`）
- 最小 CopilotKit React 前端（端到端可在浏览器演示）

### 不做（YAGNI，留后续增量）
- shared state（STATE_SNAPSHOT/DELTA）
- generative UI（前端工具渲染组件）
- HITL 工具审批（接 SDK permission 通道）
- 鉴权、多用户隔离、会话持久化（内存即可）

## 3. 架构（两层，无 CopilotRuntime 中间层）

```
浏览器 React (Vite + CopilotKit + @ag-ui/client HttpAgent)
   │  POST /agui   { threadId, runId, messages[], tools[], state }
   │  ◄── SSE 事件流（AG-UI events）
   ▼
agui-server（Spring MVC, 端口 8095）
   ├─ AgUiController     POST /agui → SseEmitter
   ├─ RunTranslator      订阅 Query(Flow<SdkMessage>) → 状态机翻译成 AG-UI 事件
   ├─ SessionStore       threadId → claude sessionId（内存 Map）
   ├─ AgUiEvent          AG-UI 事件 Java record + Jackson 序列化（手写，不依赖社区 Java SDK）
   └─ RunAgentInput      请求体 record
   ▼
ClaudeAgent.query(prompt, Options.includePartialMessages(true).resume(sid)
                          .permissionMode(BYPASS_PERMISSIONS).model(...)
                          .unsetEnv("CLAUDECODE", ...))
   ▼
本机 claude CLI（stream-json）
```

**为什么两层**：已查证 `@ag-ui/client` 的 `HttpAgent` 实例可直接传给 `<CopilotKit>`，
不必搭 CopilotRuntime（Node）。前端直连 `agui-server` 的 SSE endpoint。
> 待验证（实现阶段）：CopilotKit 当前版本 `HttpAgent` 直连前端组件的确切写法、CORS 配置。

## 4. 目录与模块结构

repo 现为单模块 jar（`com.anthropic.claude:claude-agent-sdk-java`），**主后端正依赖它**。
采用「独立子项目目录」方案，**核心模块一行不改**：

```
claude-agent-sdk-java/
├── pom.xml                 # 核心库（不动）
├── src/                    # 核心库源码（不动）
└── agui-server/            # 新增：独立 Spring Boot 子项目（独立 pom）
    ├── pom.xml             # 依赖坐标 com.anthropic.claude:claude-agent-sdk-java + spring-boot-starter-web
    ├── src/main/java/com/anthropic/claude/agent/agui/
    │   ├── AgUiServerApplication.java
    │   ├── AgUiController.java
    │   ├── RunTranslator.java
    │   ├── SessionStore.java
    │   ├── model/RunAgentInput.java
    │   └── event/AgUiEvent.java（及子类型 record）
    ├── src/test/java/...   # RunTranslator / SessionStore 单元测试
    └── web/                # 前端合并于此（Vite + React + CopilotKit）
        ├── package.json
        ├── vite.config.js  # dev 端口 5180，proxy /agui → localhost:8095
        └── src/...
```

- `agui-server` 通过**坐标**依赖核心库（需先 `mvn install` 核心库到本地 `~/.m2`）。
- 前端 `web/` 是该子项目的一部分，不单独成工程。
- artifactId：`claude-agent-sdk-agui-server`；包名：`com.anthropic.claude.agent.agui`。

## 5. 后端组件设计

### 5.1 RunAgentInput（请求体）
AG-UI 前端 POST 的 JSON。最小字段：
- `threadId`：会话线程 id（映射 claude sessionId）
- `runId`：本次运行 id
- `messages[]`：完整历史（`{id, role, content}`）；后端取**最后一条 role=user** 作为 prompt
- `tools[]` / `state` / `context`：本版接收但不使用（为后续 HITL/state 预留）

### 5.2 SessionStore
- `Map<String threadId, String claudeSessionId>`（`ConcurrentHashMap`，内存）。
- `get(threadId)` → 有则后续 `.resume()`；首轮为空。
- `put(threadId, sessionId)`：每轮收到 `SdkResultMessage.sessionId()` 后写入。

### 5.3 AgUiController
- `POST /agui` 返回 `SseEmitter`（超时设长，如 5min）。
- 流程：
  1. 解析 `RunAgentInput`，取 prompt + threadId。
  2. 发 `RUN_STARTED`。
  3. 构造 `Options`（`includePartialMessages(true)`、有 sessionId 则 `resume`、`permissionMode(BYPASS_PERMISSIONS)`、`model`、`unsetEnv(...)`）。
  4. `ClaudeAgent.query(prompt, options)` 得到 `Query`（`Flow.Publisher<SdkMessage>`）。
  5. `query.subscribe(new RunTranslator(emitter, sessionStore, threadId, runId))`。
  6. `emitter.onError/onTimeout/onCompletion` → `query.close()` 清理子进程。

### 5.4 RunTranslator（核心，有状态机）
`Flow.Subscriber<SdkMessage>`。`onNext` 按消息类型翻译并 `emitter.send()`：
- partial（`SdkPartialAssistantMessage`，需 `includePartialMessages`）按内层 `event.type` 驱动文本/工具/思考的 start→delta→end（见 §6）。
- `SdkUserMessage` 的 `ContentBlock.ToolResult` → `TOOL_CALL_RESULT`。
- `SdkResultMessage` → 写 `SessionStore` → `RUN_FINISHED`（success）/ `RUN_ERROR`（error_*）→ `emitter.complete()`。
- `onError` → `RUN_ERROR` + complete。
- 维护当前打开的 messageId / toolCallId，保证 start/end 配对。
- 加**超时守护**：N 秒无终止信号 → `RUN_ERROR` + `query.close()`。

> 待验证（实现阶段）：claude stream-json 里 tool_use 入参是否走 `input_json_delta`、thinking 是否走
> `thinking_delta`/仅 `signature_delta`。以核心库现有测试 fixture（`syn_*.json`）和真 claude 实测为准。

### 5.5 AgUiEvent
AG-UI 事件的 Java record（sealed interface + 子类型），Jackson 序列化为 SSE `data:` JSON。
事件 `type` 用 AG-UI 标准名（SNAKE_CASE 字符串），字段 camelCase。
> 待验证：事件名与字段以 AG-UI 官方 spec 为准，实现阶段逐个核对（`@ag-ui/core` 的 EventType / schema）。

## 6. 事件映射

| SDK 输出 | AG-UI 事件 |
|---|---|
| query 启动 | `RUN_STARTED`(threadId, runId) |
| partial `content_block_start`(text) | `TEXT_MESSAGE_START`(messageId, role=assistant) |
| partial `text_delta` | `TEXT_MESSAGE_CONTENT`(messageId, delta) |
| partial `content_block_stop`(text) | `TEXT_MESSAGE_END`(messageId) |
| partial `content_block_start`(tool_use) | `TOOL_CALL_START`(toolCallId, toolCallName) |
| partial `input_json_delta` | `TOOL_CALL_ARGS`(toolCallId, delta) |
| tool_use 块结束 | `TOOL_CALL_END`(toolCallId) |
| `SdkUserMessage` 的 `ContentBlock.ToolResult` | `TOOL_CALL_RESULT`(toolCallId, content) |
| thinking 块开始 | `REASONING_START` + `REASONING_MESSAGE_START` |
| 明文 `thinking_delta`（少见） | `REASONING_MESSAGE_CONTENT`(delta) |
| thinking 块结束 | `REASONING_MESSAGE_END` + `REASONING_END` |
| `SdkResultMessage` subtype=success | `RUN_FINISHED` |
| `SdkResultMessage` subtype=error_* | `RUN_ERROR` |

**典型 run 事件序列**：
`RUN_STARTED` →（[REASONING_*] / [TEXT_MESSAGE_* 一段] / [TOOL_CALL_* + TOOL_CALL_RESULT] 交替）→ `RUN_FINISHED`。

## 7. 思考占位的边界（重要）

当前 claude CLI 扩展思考多为 **redacted 模式**，stream-json 大概率只发 `signature_delta`，
**不发明文 `thinking_delta`**（核心库 `ContentBlock.RedactedThinking` 即证据）。

→ 前端能稳定显示 **「💭 思考中」状态指示**（靠 `REASONING_START`/`END` 的结构），
但 `REASONING_MESSAGE_CONTENT` **内容多半为空**。这是 claude 固有限制，非本实现缺陷。
占位仍有价值（让用户知道它在思考），只是显示不出「想了什么」。
> 待验证：CopilotKit 前端组件是否默认渲染 reasoning 事件；若不渲染，前端侧做最小适配显示「思考中」。

## 8. 关键技术选型

| 维度 | 选择 | 理由 |
|---|---|---|
| Java SSE | 手写 AG-UI 事件 record + Jackson | schema 简单可控，不依赖成熟度未验证的社区 AG-UI Java SDK |
| Web 框架 | Spring MVC + `SseEmitter` | 核心库是 `Flow` 非 reactive，单订阅线程推送最简单，不引 WebFlux |
| 前端连接 | `@ag-ui/client` `HttpAgent` 直连 | 两层架构，省掉 CopilotRuntime |
| 权限/模型 | `BYPASS_PERMISSIONS` + 可配 model（默认 sonnet） | 不做 HITL，工具自动执行 |
| 会话 | SDK `.resume(sessionId)` + 内存 SessionStore | 不自己发明会话机制 |
| 环境隔离 | `.unsetEnv("CLAUDECODE","CLAUDE_CODE_ENTRYPOINT","CLAUDE_CODE_EXECPATH","TMUX")` | 沿用核心库已修的坑，防从 CC 环境启动时子 claude 异常 |

## 9. 错误处理 / 边界

- `query` 抛异常 / `SdkResultMessage.isError()` → `RUN_ERROR`(message=stderr/subtype) + complete。
- 订阅模式超时守护：N 秒无 `SdkResultMessage` → `RUN_ERROR` + `query.close()`。
- SSE 断连（`onError`/`onTimeout`）→ `query.close()` 释放 claude 子进程。
- prompt 为空（无 user message）→ 400。
- 日志：query 启动前打 prompt 摘要 + threadId + 是否 resume；结束打 subtype + sessionId + cost/turns。

## 10. 测试策略（TDD）

- **单元（主力）**：构造 `SdkMessage` 序列（仿核心库 `syn_*.json` fixture）喂给 `RunTranslator`，
  断言产出的 AG-UI 事件序列正确：start/delta/end 配对、tool 生命周期完整、reasoning 占位、
  result→finished/error。先写测试再写实现。
- **单元**：`SessionStore` 首轮无 sessionId、次轮 resume 取值。
- **集成（默认 @Ignore，`-D` 开关）**：真 claude 跑一轮，断言 SSE 流含 `TEXT_MESSAGE_CONTENT` + `RUN_FINISHED`。
- **前端**：浏览器手动验证打字机 + 工具卡 + 多轮 + 思考占位。
- JUnit 4（与核心库一致）。

## 11. 配置

- 后端端口 8095（避开主后端 8080 / capsule-code 8082）。
- 前端 Vite dev 5180（避开 5173/5174），proxy `/agui` → `http://localhost:8095`。
- model、超时秒数、permissionMode 走配置（`application.properties` / 环境变量）。

## 12. 待实现阶段验证清单（诚实标注）

1. CopilotKit `HttpAgent` 直连前端组件的确切 API（当前版本）+ CORS。
2. AG-UI 事件名与字段的精确 schema（对照 `@ag-ui/core`）。
3. claude stream-json 中 tool_use 入参（`input_json_delta`?）与 thinking（`thinking_delta` vs 仅 `signature_delta`）的实际 wire 结构。
4. CopilotKit 是否渲染 reasoning 事件，否则前端最小适配。
5. 核心库需先 `mvn install` 到本地 `~/.m2`，`agui-server` 才能按坐标解析依赖。
