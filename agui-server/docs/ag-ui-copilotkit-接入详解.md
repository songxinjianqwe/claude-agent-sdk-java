# AG-UI × CopilotKit 接入详解：把一个驱动 Claude CLI 的 Java SDK 接成全栈 Agent 应用

> 一次完整实践的记录：把 [`claude-agent-sdk-java`](../../)（一个驱动本机 `claude` CLI、走
> stream-json 的 Java SDK）接成 **AG-UI 协议后端 + CopilotKit 前端**，在浏览器里跑通带
> 「文本打字机 / 工具调用过程 / 思考 / 多轮对话」的聊天界面。
>
> 这份文档既是「怎么做」，也是「为什么这样做」——把过程中对 AG-UI / CopilotKit 的理解和踩的坑都记下来。

## TL;DR（结论先行）

- **CopilotKit 是「Agent 的前端栈」，发起了 AG-UI 协议**；AG-UI 是 Agent ↔ 前端的开放协议
  （SSE + ~16 种事件）。类比：**MCP 接工具、A2A 接 Agent、AG-UI 接 UI**。
- **闭源 / 任意后端都能接 AG-UI**，前提是它暴露了（最好是流式的）可编程 API —— AG-UI 标准化的是
  「传输格式」，**不凭空制造「过程」**：后端只给最终结果，前端就只能展示最终结果。
- **Anthropic 官方接 AG-UI 的是 Claude *Agent SDK*（编程库），不是 Claude Code CLI（成品命令行）**。
  本实践用的就是 Agent SDK 思路的一个 Java 实现。
- **CopilotKit 现成聊天组件的标准接法要一个 Node `CopilotRuntime` 中间层**，但 CopilotKit **1.59
  的 v2 API（`selfManagedAgents`）能让前端纯直连 `HttpAgent`**，做成两层（React → Java），省掉 Node。
- **后端零改动适配 AG-UI**：wire 格式（`message.content` 是 string、事件名 `SNAKE_CASE` + 字段
  `camelCase`）与 AWS Bedrock 官方 AG-UI 契约逐字段一致。

## 一、概念厘清

> 这类问题别凭印象答——CopilotKit / AG-UI / Claude CLI 都在快速演进，下面是 2026-06 实测的结论。

### 1. CopilotKit 与 AG-UI

- **CopilotKit**：开源的「Agent 前端栈」。它要解决的痛点是：Agent 框架擅长后端推理编排，但
  「怎么把 Agent 的流式输出 / 工具调用 / 中间状态 / 人工介入渲染到真实 UI」这一层是空白，每家都重复造轮子。
- **AG-UI（Agent–User Interaction）**：CopilotKit 发起的开放协议，**事件驱动、默认 SSE 传输、约 16
  种标准事件**：
  - 生命周期：`RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR`
  - 文本：`TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT`(delta) / `TEXT_MESSAGE_END`
  - 工具：`TOOL_CALL_START` / `TOOL_CALL_ARGS`(delta) / `TOOL_CALL_END` / `TOOL_CALL_RESULT`
  - 思考：`REASONING_START` / `REASONING_MESSAGE_*` / `REASONING_END`
  - 状态：`STATE_SNAPSHOT` / `STATE_DELTA`
- AG-UI 已被多家云厂商和 Agent 框架接入（LangGraph、CrewAI、Google ADK、Mastra 等）。

### 2. 任意后端 / 闭源 agent 能不能接？

**能。** AG-UI = 协议规范 + 多语言 SDK + 一批官方预接的框架。三种接入路径：

1. **原生**：框架自己 emit AG-UI 事件（你白嫖）；
2. **中间件（middleware）**：你在中间插一层，把任意后端（含只有 HTTP API 的闭源服务）翻译成 AG-UI 事件；
3. **自定义 SDK**：用 AG-UI 的 SDK 从零构造事件流。

> ⚠️ 关键边界：**中间件是翻译官，不是造假者。** 你能渲染多少「过程」，取决于后端 API 暴露多少：
> - 后端给流式 token + 工具事件 → 你能翻译出完整过程，前端渲染打字机 + 工具卡；
> - 后端只给黑盒最终结果 → 你只能 emit 一条完整消息，变不出「过程感」。
>
> 这也解释了「把 Agent 过程渲染到 UI」这件事的真相：前端那一半 CopilotKit 替你扛了，但**另一半的前提
> 是后端 Agent 本身愿意 / 能够把过程吐出来**。

### 3. Claude Agent SDK 是什么

- Anthropic **官方**包（Node `@anthropic-ai/claude-agent-sdk` / Python `claude-agent-sdk`）。
- **本质**：它不是直连 HTTP API 的 SDK，而是**在本机 spawn `claude` CLI、走 stream-json 双向协议**的
  封装，按 **claude.ai 订阅额度**计费。
- 跟「直连 HTTP API、按 API key 计费」的官方 SDK 是两回事。
- `claude-agent-sdk-java`（本仓库）就是这条思路的 Java 实现：把 CLI 的 stream-json NDJSON 解析成
  结构化的 `SdkMessage` 流（带 `Flow` 背压、token 级 partial、工具生命周期分离）。

### 4. 官方接的是 Agent SDK，不是「Claude Code CLI 套壳」

- AG-UI 接的是**编程库**（Agent SDK），因为库本来就是为「被程序集成」设计的。
- 成品 CLI（Claude Code）自带终端 UI，社区套壳项目通常**直接消费 `stream-json` 自己渲染**更省事，
  绕一层 AG-UI 没收益 —— 不是接不了，是性价比不划算。

## 二、实践架构

### 两层架构（无 CopilotRuntime）

```
浏览器 React (Vite + CopilotKit v2 <CopilotChat> + @ag-ui/client HttpAgent)
   │  POST /agui  { threadId, runId, messages[] }     （vite proxy /agui → 8095）
   │  ◄── SSE 事件流（AG-UI events）
   ▼
agui-server (Spring MVC, :8095)
   ├─ AgUiController   POST /agui → SseEmitter（终结事件后主动 complete + 超时守护）
   ├─ RunTranslator    订阅 Query(Flow<SdkMessage>) 状态机 → AG-UI 事件写入 Consumer<AgUiEvent>
   ├─ SessionStore     threadId → claude sessionId（内存，多轮 .resume()）
   └─ AgUiEvent        手写 AG-UI 事件 record + Jackson（不依赖社区 AG-UI Java SDK）
   ▼
ClaudeAgent.query(prompt, includePartialMessages(true).permissionMode(BYPASS_PERMISSIONS)
                          .resume(sid).unsetEnv("CLAUDECODE", ...))
   ▼
本机 claude CLI（stream-json）
```

### 后端：SdkMessage → AG-UI 事件映射

| SDK 输出 | AG-UI 事件 |
|---|---|
| query 启动 | `RUN_STARTED`(threadId, runId) |
| partial `content_block_start`(text) | `TEXT_MESSAGE_START`(messageId, role) |
| partial `text_delta` | `TEXT_MESSAGE_CONTENT`(delta) ← 打字机 |
| partial `content_block_stop`(text) | `TEXT_MESSAGE_END` |
| `content_block_start`(tool_use) | `TOOL_CALL_START`(toolCallId, toolCallName) |
| `input_json_delta` | `TOOL_CALL_ARGS`(delta) |
| tool_use 块结束 | `TOOL_CALL_END` |
| `SdkUserMessage` 里的 `tool_result` | `TOOL_CALL_RESULT` |
| `thinking` 块 | `REASONING_START` / `_MESSAGE_*` / `_END` |
| `SdkResultMessage` success / error | `RUN_FINISHED` / `RUN_ERROR` |

`RunTranslator` 是 `Flow.Subscriber<SdkMessage>`，按 content block 的 `index` 跟踪当前打开的块
（text / tool_use / thinking）做 start/end 配对。它把事件写入一个 `Consumer<AgUiEvent>` sink，
**核心翻译逻辑因此脱离 HTTP，可单元测试**（仓库里有 16 个单测）。

### 前端：CopilotKit v2 直连

```jsx
import { CopilotKitProvider, CopilotChat } from '@copilotkit/react-core/v2'
import { HttpAgent } from '@ag-ui/client'
import '@copilotkit/react-ui/v2/styles.css'

const agent = new HttpAgent({
  url: '/agui',
  fetch: (...args) => window.fetch(...args),   // ← 见踩坑 2
})

export default function App() {
  return (
    <CopilotKitProvider selfManagedAgents={{ default: agent }}>
      <CopilotChat agentId="default" />
    </CopilotKitProvider>
  )
}
```

`selfManagedAgents` 接受 `AbstractAgent`（`HttpAgent` 继承它），让前端**直连，不需要
`/api/copilotkit` 的 Node CopilotRuntime**。`<CopilotChat>` 自带工具卡（`▸ Bash Done` 可展开）、
markdown、reasoning 渲染。

### 实测（带 Network 证据）

干净浏览器里 `POST /agui → 200 OK`，console 0 errors，依次渲染：文本打字机、Bash 工具卡、
markdown 内联 code、多轮 resume（跨轮记住「42」）。**思考实测有明文内容**（不是全 redacted，比预期乐观）。

## 三、踩坑表

| 症状 | 根因 | 修复 |
|---|---|---|
| CopilotKit 现成组件似乎必须 Node runtime / `runtimeUrl` | 标准 quickstart 走 `<CopilotKit runtimeUrl="/api/copilotkit">` → `@copilotkit/runtime`(Node) → HttpAgent | 用 **v2 API** 的 `selfManagedAgents` / `agents__unsafe_dev_only`（都接受 `AbstractAgent`）纯前端直连 |
| 前端发消息没回复 / `TypeError: Illegal invocation` / `agent_run_failed` | **`HttpAgent` 默认 fetch 调用丢失 window 上下文**（`fetch` 必须以 `window` 为 `this`） | `HttpAgentConfig.fetch` 传 `(...a) => window.fetch(...a)` |
| JUnit 4 测试不被执行 / surefire 跑 0 个 | **Spring Boot 3 的 `spring-boot-starter-test` 只带 JUnit 5**，没 vintage engine 就发现不了 JUnit 4 | pom 加 `org.junit.vintage:junit-vintage-engine`(test) |
| 不确定 AG-UI 的 `message.content` 类型 / 事件命名 | 文档零散 | **AWS Bedrock 官方契约**印证：content 是 **string**、事件名 **SNAKE_CASE** + 字段 **camelCase**；据此手写事件 record，后端零改动 |
| 从 Claude Code / tmux 里启动后端，子 claude 行为异常 | 子进程继承 `CLAUDECODE` / `TMUX` 等，claude 以「已在 CC 内」模式跑 | `Options.unsetEnv("CLAUDECODE","CLAUDE_CODE_ENTRYPOINT","CLAUDE_CODE_EXECPATH","TMUX")` |
| SSE 流不结束 / 客户端挂住 | 不确定 SDK 是否在 result 后回调 `onComplete` | 不赌 `onComplete`：**sink 收到 `RUN_FINISHED`/`RUN_ERROR` 就主动 `emitter.complete()`** + 超时守护兜底 |

## 四、范围与取舍

**本次做了**：文本流 / 工具调用过程 / 思考占位 / 多轮 resume —— 一个真正能用的聊天闭环。

**留作增量没做**：HITL 工具审批、shared state（`STATE_*`）、generative UI。这些是 CopilotKit 的高级
卖点，但 Claude 原生没有对应概念（它只有「文本」+「自己执行的工具」），要在 adapter 层额外发明机制
（比如给 Claude 注册特殊工具、或维护一份独立 state），性价比低。对「把 Claude 接进来 + 能聊天看过程」
这个核心目标不是必需的。

---

配套文档：
- [`../README.md`](../README.md) — agui-server 使用说明（架构、快速开始、API）
- `../../docs/superpowers/specs/` 与 `plans/` — 设计文档与 TDD 实现计划
