# agui-server

把 [`claude-agent-sdk-java`](../) 接入 **[AG-UI 协议](https://docs.ag-ui.com)** 的参考实现，外加一个
最小的 **CopilotKit** 前端。

让你用 **claude.ai 订阅额度**的 Claude，通过浏览器里的 CopilotKit 聊天界面对话——看到**文本打字机、
工具调用过程、思考、多轮对话**，全程不消耗 API key 额度。

> 核心库把本机 `claude` CLI 的 stream-json 解析成了结构化 `SdkMessage`（带 `Flow` 背压、token 级
> partial、工具生命周期）。本子项目把这个流**翻译成 AG-UI 事件**，经 SSE 暴露，前端直接连。

## 架构（两层，无 Node 中间层）

```
浏览器 React (Vite + CopilotKit v2 + @ag-ui/client HttpAgent)
   │  POST /agui  { threadId, runId, messages[] }      （vite proxy /agui → 8095）
   │  ◄── SSE 事件流（AG-UI events）
   ▼
agui-server (Spring Boot, :8095)
   ├─ AgUiController   POST /agui → SseEmitter
   ├─ RunTranslator    Flow.Subscriber<SdkMessage> 状态机 → AG-UI 事件
   ├─ SessionStore     threadId → claude sessionId（多轮 .resume()）
   └─ AgUiEvent        AG-UI 事件模型（手写 record + Jackson）
   ▼
ClaudeAgent.query(...)  →  本机 claude CLI（stream-json）
```

CopilotKit 的现成聊天组件**通常**要一个 Node `CopilotRuntime` 做中转；这里用 CopilotKit **1.59 的
v2 API**（`selfManagedAgents`）让前端**直连 `HttpAgent`**，省掉 Node 层。

## 快速开始

前置：本机装了并登录了 `claude` CLI；核心库已 install 到本地 Maven 仓库。

```bash
# 0) 在仓库根先把核心库装到本地 ~/.m2
cd .. && mvn install -DskipTests && cd agui-server

# 1) 启动后端（:8095）
mvn spring-boot:run

# 2) 启动前端（:5180），另开一个终端
cd web && npm install && npm run dev
```

打开 <http://localhost:5180>，开始聊天。

纯后端冒烟（不用前端，直接看 SSE 事件流）：

```bash
curl -N -X POST http://localhost:8095/agui \
  -H 'Content-Type: application/json' \
  -d '{"threadId":"t1","runId":"r1","messages":[{"id":"1","role":"user","content":"用一句话介绍你自己"}]}'
```

## 它怎么工作

### 后端：SdkMessage → AG-UI 事件

`RunTranslator` 按 content block 的 `index` 跟踪当前打开的块，逐条翻译：

| SDK 输出 | AG-UI 事件 |
|---|---|
| query 启动 | `RUN_STARTED` |
| partial `text_delta` | `TEXT_MESSAGE_START` / `_CONTENT`(delta) / `_END` |
| `tool_use` 块 + `input_json_delta` | `TOOL_CALL_START`(toolCallId, toolCallName) / `_ARGS`(delta) / `_END` |
| `SdkUserMessage` 的 `tool_result` | `TOOL_CALL_RESULT` |
| `thinking` 块 | `REASONING_START` / `_MESSAGE_*` / `_END` |
| `SdkResultMessage` | `RUN_FINISHED` / `RUN_ERROR` |

翻译逻辑写入一个 `Consumer<AgUiEvent>` sink（Controller 把 sink 接到 `SseEmitter`），因此**核心翻译完全
脱离 HTTP，可单元测试**（见 `RunTranslatorTest`）。

AG-UI wire 格式（`message.content` 为 string、事件名 `SNAKE_CASE`、字段 `camelCase`）与
[AWS Bedrock 的 AG-UI 契约](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/runtime-agui-protocol-contract.html)
一致。

### 前端：CopilotKit v2 直连

```jsx
import { CopilotKitProvider, CopilotChat } from '@copilotkit/react-core/v2'
import { HttpAgent } from '@ag-ui/client'
import '@copilotkit/react-ui/v2/styles.css'

const agent = new HttpAgent({
  url: '/agui',
  fetch: (...args) => window.fetch(...args),   // 必需，见下方「已知坑」
})

export default function App() {
  return (
    <CopilotKitProvider selfManagedAgents={{ default: agent }}>
      <CopilotChat agentId="default" />
    </CopilotKitProvider>
  )
}
```

## 已知坑

1. **`HttpAgent` 必须传 window-bound 的 `fetch`**。否则浏览器抛 `TypeError: Illegal invocation`
   （默认实现调用 `fetch` 时丢失了 `window` 这个 `this`）。用 `fetch: (...a) => window.fetch(...a)`。
2. **JUnit 4 需要 vintage engine**。Spring Boot 3 的 `spring-boot-starter-test` 只带 JUnit 5，pom 额外
   加了 `org.junit.vintage:junit-vintage-engine` 才能让 surefire 发现并执行 JUnit 4 测试。
3. **从 Claude Code / tmux 里启动后端**时，子 `claude` 会继承 `CLAUDECODE` 等环境变量而行为异常；已用
   `Options.unsetEnv(...)` 剥离（生产由 launchd 启动则本就无这些变量）。

## 范围

**做**：文本流式、工具调用过程、思考占位、多轮 resume。`permissionMode = BYPASS_PERMISSIONS`
（工具自动执行）。

**不做**（留作后续增量）：HITL 工具审批、shared state（`STATE_*`）、generative UI、鉴权、持久化。
这些是 CopilotKit 的高级能力，但 Claude 原生没有对应概念，需在 adapter 层额外发明机制。

## 文档

- [**AG-UI × CopilotKit 接入详解**](docs/ag-ui-copilotkit-接入详解.md) — 概念厘清 + 架构 + 踩坑全记录（**推荐先读**，理解「为什么这样做」）
- [**Generative UI 是怎么回事**](docs/generative-ui详解.md) — 用三个例子 + 时序图讲透 generative UI / 前端工具 / HITL，核心是「谁执行工具 ≠ 谁渲染工具」
- `../docs/superpowers/specs/2026-06-07-agui-adapter-design.md` — 设计
- `../docs/superpowers/plans/2026-06-07-agui-adapter-backend.md` — 后端实现计划（TDD）
