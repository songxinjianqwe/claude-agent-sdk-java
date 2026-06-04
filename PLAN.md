# Claude Agent SDK for Java — 实现计划（PLAN）

> 状态：**已实现并通过验证**（2026-06-02 完成全部 8 阶段；`mvn test` 83 测试全绿，含 2 个真 claude 集成测试 `-Dclaude.sdk.it=true`；in-process MCP 工具已被真 claude 实际调用。用法见 `README.md`，wire 规格见 `docs/wire-protocol.md`）
> 起草：2026-06-02
> 定位：**独立 Maven 库**（`~/dev/java/claude-agent-sdk-java/`），不依赖任何业务代码
> 目标：**1:1 对齐** Node `@anthropic-ai/claude-agent-sdk` 的编程接口
> 已与用户确认的三项抉择：**完整 SDK** / **Reactive `Flow.Publisher`** / **独立通用库**

---

## 1. 目标与范围

复刻 Node Agent SDK 的**完整公开接口**，让 Java 用户用近乎一致的心智模型驱动 Claude Code：

- `query(prompt, options)` → 响应式消息流（`Flow.Publisher<SdkMessage>`）
- 完整 `Options`（~60 字段）、完整 `SdkMessage` 变体（30+）、`canUseTool` 工具审批、`hooks`、in-process MCP（`tool()`/`createSdkMcpServer()`）、`Query` 运行时控制方法（interrupt/setPermissionMode/setModel/mcpServerStatus/streamInput…）
- JDK 17、Jackson、零业务依赖、零重型框架依赖（不引 Spring/Reactor，用 JDK 自带 `java.util.concurrent.Flow`）
- **质量优先**：完整 JUnit4 单测；针对我们 Capsule Code 手搓踩过的坑预先规避

**非目标**：不 bundle claude 二进制（用系统 PATH 的 `claude` 或 `pathToClaudeCodeExecutable` 指定）；不做 GUI；不实现 Anthropic Messages API 客户端（那是另一个 SDK）。

---

## 2. 底层机制（要复刻的对象）

Node SDK 的 `query()` 本质（前期调研已摸清，见 `my-ai-playground/devdocs/0602-claudecode接入方式横向对比/`）：

```
query() ──spawn──► claude --print --input-format=stream-json --output-format=stream-json [options...]
            │
            ├─ stdin  ← 写 NDJSON user message（多轮 streamInput）
            ├─ stdout → 读 NDJSON 事件流（assistant/thinking/tool_use/result/stream_event/…）
            └─ control protocol（穿插在同一对 stdio 上）：
                 control_request:  can_use_tool / initialize / mcp_message / hook_callback …
                 control_response:  allow/deny / 配置 / 结果
```

- **Options → 启动参数 + initialize**：大部分 options 映射成命令行 flag，少量走 initialize control_request。
- **必须显式 `--permission-mode default`**（否则 `defaultMode:auto` 智能分类器会跳过审批——Capsule 实测坑）。
- 进程是 headless（`--print`），处理边界要小心退出时机。

---

## 3. Java API 设计（对齐 Node + Java 化）

### 3.1 入口

```java
// 单次 query（prompt 为字符串）——一次性
Query q = ClaudeAgent.query("帮我读 README", Options.builder()
        .model("claude-opus-4-7")
        .cwd("/path/to/project")
        .allowedTools("Read", "Grep")
        .permissionMode(PermissionMode.DEFAULT)
        .canUseTool((tool, input, ctx) -> PermissionResult.allow())
        .build());

// Query 实现 Flow.Publisher<SdkMessage>，订阅消费
q.subscribe(new Flow.Subscriber<>() { ... });
```

- `Query extends Flow.Publisher<SdkMessage>`（对齐 Node 的 `Query extends AsyncGenerator<SDKMessage>`，用 Flow 表达"异步消息流"）。
- 提供一个**便利适配层**：`q.toStream()` 返回阻塞 `Stream<SdkMessage>` / `q.blockingIterator()`，给不想写 Subscriber 的用户（背压默认 `Long.MAX_VALUE`）。

### 3.2 核心类型映射

| Node | Java | 说明 |
|---|---|---|
| `query(prompt, options)` | `ClaudeAgent.query(String\|Publisher<SdkUserMessage>, Options)` | 静态入口 |
| `Query`（AsyncGenerator + 方法） | `interface Query extends Flow.Publisher<SdkMessage>` + 控制方法 | |
| `Options`（对象字面量） | `Options`（**builder 模式**，不可变） | ~60 字段分批落地 |
| `SDKMessage`（union） | `sealed interface SdkMessage permits …` + **record** 实现 | JDK17 穷尽 + 模式匹配 |
| `CanUseTool` | `@FunctionalInterface CanUseTool` → `PermissionResult` | |
| `PermissionResult`（allow/deny） | `sealed interface PermissionResult`（`Allow`/`Deny` record） | |
| `PermissionMode`（6 值） | `enum PermissionMode { DEFAULT, ACCEPT_EDITS, BYPASS_PERMISSIONS, PLAN, DONT_ASK, AUTO }` | |
| `McpServerConfig`（5 种） | `sealed interface McpServerConfig`（Stdio/Sse/Http/Sdk/ClaudeAiProxy record） | |
| `tool(name,desc,schema,handler)` | `SdkMcpTool.of(name, desc, schema, handler)` | in-process MCP |
| `createSdkMcpServer({...})` | `SdkMcpServer.create(name, version, tools)` | |
| `hooks` | `Map<HookEvent, List<HookMatcher>>` | |
| `AgentDefinition` / `ThinkingConfig` / `SandboxSettings` | 对应 record | |

### 3.3 消息类型（`SdkMessage` sealed 树）

核心变体先做（覆盖 90% 场景），其余按需补：

```
sealed interface SdkMessage permits
    SdkAssistantMessage, SdkUserMessage, SdkResultMessage,
    SdkSystemMessage, SdkPartialAssistantMessage /*stream_event*/,
    SdkToolProgressMessage, SdkPermissionDeniedMessage,
    SdkUnknownMessage /*兜底：未知 type 不崩，保留 raw*/ , ...
```

- 用 Jackson `@JsonTypeInfo(use=NAME, property="type")` + `@JsonSubTypes` 做多态反序列化；`type` 不认识时落到 `SdkUnknownMessage`（**协议漂移不崩**，参考 companion 漂移监控精神）。
- `result` 还要按 `subtype` 细分（success/error_max_turns/…）。

---

## 4. 模块 / 包结构

```
claude-agent-sdk-java/
├── pom.xml                    # JDK17, jackson-databind, junit:4.13.2, (test) 无重依赖
├── PLAN.md                    # 本文件
├── README.md
└── src/
    ├── main/java/com/anthropic/claude/agent/
    │   ├── ClaudeAgent.java            # query() 静态入口
    │   ├── Query.java                  # 接口（Flow.Publisher + 控制方法）
    │   ├── Options.java                # builder
    │   ├── message/                    # SdkMessage sealed 树 + records
    │   ├── permission/                 # CanUseTool, PermissionResult, PermissionMode
    │   ├── mcp/                        # SdkMcpTool, SdkMcpServer, McpServerConfig（in-process MCP）
    │   ├── hooks/                      # HookEvent, HookMatcher, HookResponse
    │   ├── agent/                      # AgentDefinition, ThinkingConfig, SandboxSettings…
    │   ├── transport/
    │   │   ├── ProcessTransport.java   # spawn + stdio 生命周期
    │   │   ├── NdjsonCodec.java        # 逐行 NDJSON 编解码（Jackson）
    │   │   └── ControlProtocol.java    # control_request/response 双向路由
    │   └── internal/                   # QueryImpl, Flow 实现, Jackson 配置, 日志
    └── test/java/...                   # JUnit4 + mock-claude 脚本
```

---

## 5. 关键技术决策

1. **`Flow.Publisher` 实现**：自定义 Publisher。一个**读线程**阻塞读 stdout、逐行解析成 `SdkMessage`，按订阅者 `request(n)` 的 demand 投递（背压）。内部用有界队列 + demand 计数，溢出时按策略（默认缓冲，可配丢弃/报错）。不引 Reactor（JDK Flow 够用）。
2. **进程生命周期**：直接用 `ProcessBuilder` + `process.getInputStream()/getOutputStream()`（**父子进程，stdio 直连 pipe，不用 FIFO/tmux**——SDK 不需要"进程活得比宿主久"，宿主退则 claude 退，和 Node SDK 一致）。退出检测用 `process.onExit()`（CompletableFuture）+ `destroyForcibly()` 兜底。**规避 Capsule 的 FIFO `<>` 退出时机坑**（那是为进程解耦付的代价，SDK 不需要）。
3. **control protocol 双向路由**：`ControlProtocol` 维护 `requestId → 待回调` 表。stdout 来的 `can_use_tool` control_request → 调用户 `CanUseTool` 回调 → 把 `PermissionResult` 写成 control_response 到 stdin。`initialize`/`mcp_message`/`hook_callback` 同理。**所有写 stdin 的操作串行化**（单写线程 + 队列），避免交错。
4. **NDJSON 解析**：`BufferedReader` 逐行 + Jackson；`FAIL_ON_UNKNOWN_PROPERTIES=false`（**宽松解析，扛字段新增**）。
5. **Options → 参数映射**：集中在一处 `Options.toCliArgs()`，每个字段一行映射 + 注释对应 Node 字段，便于追协议。强制注入 `--input/output-format=stream-json --verbose`（stream-json 要求 verbose）+ 按需 `--permission-mode default`。
6. **in-process MCP（最难）**：`tool()`/`createSdkMcpServer()` 在 Java 侧实现一个最小 MCP server（JSON-RPC 处理 `tools/list`+`tools/call`），通过 control protocol 的 `mcp_message`（`sdkMcpServers`）与 claude 通信。这是阶段 7，独立攻关。

---

## 6. 分阶段实现计划（每阶段必须可编译 + 单测绿 + 可演示）

| 阶段 | 内容 | 验证（成功标准） |
|---|---|---|
| **0 骨架** | Maven 工程、Jackson、JUnit4、mock-claude 测试脚手架 | `mvn test` 跑通空测试；mock-claude 能按脚本吐 NDJSON |
| **1 消息类型** | `SdkMessage` sealed 树 + 核心 records + Jackson 多态解析 + `SdkUnknownMessage` 兜底 | 单测：喂各类固定 NDJSON 行 → 解析成正确变体；未知 type → Unknown 不抛 |
| **2 进程 + 流 + query MVP** | `ProcessTransport`（spawn+读 stdout→Flow+写 stdin）+ `ClaudeAgent.query` 单轮 | 单测（mock-claude）：query("hi") → 收到 assistant + result；进程退出干净；背压 request(n) 生效 |
| **3 Options + 多轮** | `Options` builder + `toCliArgs()`（核心字段）+ `streamInput` 多轮 | 单测：Options 映射出正确 argv；多轮对话顺序正确 |
| **4 control: 审批** | `ControlProtocol` + `can_use_tool` → `CanUseTool` 回调 → control_response（allow/deny+updatedInput）；`--permission-mode default` | 单测：mock-claude 发 can_use_tool → 回调被调 → claude 收到正确 response；deny+interrupt 生效 |
| **5 Query 控制方法** | interrupt / setPermissionMode / setModel / setMaxThinkingTokens / mcpServerStatus / initializationResult / supportedModels | 单测：各 control_request 正确发出、response 正确解析 |
| **6 hooks** | HookEvent + HookMatcher + 走 hook control/回调 | 单测：PreToolUse/PostToolUse 回调被触发 |
| **7 in-process MCP** | `tool()`/`createSdkMcpServer()` + Java MCP server over `mcp_message` | 单测：注册一个 tool → claude 调用 → Java handler 执行 → 结果回流 |
| **8 补全 + 集成** | 剩余 Options 字段、AgentDefinition/ThinkingConfig/Sandbox、错误细分、README、（可选）真 claude smoke test | 全单测绿；可选真 claude 一次端到端 |

> 每阶段是一个独立可交付单元；阶段 1-4 跑通即"能用的 SDK 核心"。

---

## 7. 测试策略

- **mock-claude 脚本**（核心）：一个不依赖真 claude/网络的可执行（bash 或小 Java 程序），按测试用例预设：读 stdin、按脚本吐 NDJSON 到 stdout（含 control_request）。通过 `pathToClaudeCodeExecutable` 指向它。→ 可确定性地测解析、多轮、审批、interrupt、退出、错误、背压。
- **JUnit4**（`junit:4.13.2`，遵循项目约定）。
- 覆盖：①各消息变体解析 ②未知 type 不崩 ③单轮/多轮 ④can_use_tool allow/deny/updatedInput ⑤interrupt ⑥进程异常退出/超时 ⑦背压 ⑧Options→argv 映射 ⑨in-process MCP 往返。
- **可选集成测试**：真 `claude` headless smoke（`@Ignore` 默认跳过，需登录环境手动开），验证对真 CLI 的兼容。

---

## 8. 质量保障（用 Capsule 踩过的坑反向加固）

| 已知坑（来自 Capsule/调研） | SDK 里的预防 |
|---|---|
| FIFO `<>` 双向重定向导致进程退出后 wrapper 不退 | **不用 FIFO**，直接 Process pipe；退出检测靠 `process.onExit()` |
| `--permission-prompt-tool stdio` 必须配 `--permission-mode default` | `Options` 映射时，启用 canUseTool 即强制 `--permission-mode default` + 走 control（不用 prompt-tool）|
| thinking redacted（只发 signature_delta） | 不期望明文；partial 事件如实透传，不假设有 thinking 文本 |
| 协议漂移（claude 高频更新） | 宽松解析 + `SdkUnknownMessage` 兜底 + 未知 control subtype 记 warn 不崩；`toCliArgs()`/消息映射集中、注释对应 Node 字段，便于追版本 |
| stream-json 解析半行/边角 | `BufferedReader` 按行；空行/非 JSON 行跳过并记日志 |
| 写 stdin 交错 | 单写线程 + 队列串行化所有 stdin 写 |
- 通用：每个边界（spawn/退出/解析失败/control 超时）加日志 + 异常（不吞、不返回 null，抛具体异常）+ 必要断言。

---

## 9. 风险与未决点

1. **in-process MCP（阶段 7）最复杂**：要在 Java 实现一个 MCP server（JSON-RPC `tools/list`+`tools/call`）并经 `mcp_message` 路由。可能引入 [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) 或自己写最小实现——需评估。
2. **control protocol 精确消息格式**：`initialize` / `can_use_tool` / `mcp_message` 的逐字段 JSON，需从反编译源码（`claude-code-source/src/cli/structuredIO.ts`、`QueryEngine.ts`）**逐字段核准**——这是最关键、最易错的地方，开发阶段 4 前必须先读准。
3. **背压 + 阻塞读 stdout 的协调**：读线程速度 vs 订阅者 demand，需有界缓冲 + 明确溢出策略。
4. **版本漂移**：接口是 **2026-06 快照**（Node SDK 文档 + 反编译 v2.1.15x）；claude 升级可能变 flag/消息，靠宽松解析 + 集中映射缓冲。
5. **~60 Options + 30+ 消息变体工作量大**：靠分阶段（先核心 90%，长尾按需补）。

---

## 10. 环境前提

- JDK 17+、Maven。
- 系统 PATH 有 `claude`（或 `Options.pathToClaudeCodeExecutable` 指定）。
- 鉴权复用 CLI 登录态（claude.ai 订阅）或 `ANTHROPIC_API_KEY`——SDK 不另管鉴权。
- 注意计费：订阅用户经 SDK / `claude -p` 走「专属月度 Agent SDK credit」（2026-06-15 起，见 0602 第十一节）。

---

## 11. 接口对齐基准（Node API surface 摘要，2026-06 快照）

> 实现时以此为对齐基准，逐字段核对。完整版见 `docs.claude.com` Agent SDK TS reference。

- **query**：`query({prompt: string|AsyncIterable<SDKUserMessage>, options?: Options}): Query`
- **Options 关键字段**：`model, systemPrompt({type:'preset',preset:'claude_code',append}), appendSystemPrompt, allowedTools[], disallowedTools[], permissionMode, canUseTool, hooks, mcpServers{}, settingSources[], cwd, additionalDirectories[], resume, continue, forkSession, sessionId, maxTurns, maxBudgetUsd, thinking({type:'enabled'|'disabled'|'adaptive', budgetTokens}), maxThinkingTokens(deprecated), includePartialMessages, includeHookEvents, pathToClaudeCodeExecutable, executable, executableArgs[], extraArgs{}, env{}, stderr(cb), abortController, fallbackModel, agents{}, agent, plugins[], skills, strictMcpConfig, allowDangerouslySkipPermissions, effort, betas[], outputFormat, persistSession, debug, debugFile, title, sandbox, taskBudget`
- **SdkMessage 变体**：`assistant, user, user(replay), result(subtype: success|error_max_turns|error_during_execution|error_max_budget_usd|error_max_structured_output_retries), system(subtype: init|compact_boundary|…), stream_event(=partial), tool_progress, permission_denied, status, compact_boundary, hook_started/progress/response, task_*, session_state_changed, notification, ...`
- **CanUseTool**：`(toolName, input, {signal,suggestions,blockedPath,decisionReason,toolUseID,agentID}) => Promise<PermissionResult>`；`PermissionResult = {behavior:'allow', updatedInput?, updatedPermissions?} | {behavior:'deny', message, interrupt?}`
- **PermissionMode**：`default | acceptEdits | bypassPermissions | plan | dontAsk | auto`
- **Query 方法**：`interrupt, close, setPermissionMode, setModel, setMaxThinkingTokens, applyFlagSettings, initializationResult, supportedCommands, supportedModels, supportedAgents, mcpServerStatus, accountInfo, reconnectMcpServer, toggleMcpServer, setMcpServers, streamInput, stopTask, rewindFiles`
- **tool**：`tool(name, description, inputSchema, handler, extras?) → SdkMcpToolDefinition`
- **createSdkMcpServer**：`createSdkMcpServer({name, version?, tools?}) → McpSdkServerConfigWithInstance`
- **McpServerConfig**：`{type:'stdio',command,args,env} | {type:'sse',url,headers} | {type:'http',url,headers} | {type:'sdk',name,instance} | {type:'claudeai-proxy',url,id}`

---

## 12. 下一步（待用户拍板后）

1. 先读准 `structuredIO.ts` / `QueryEngine.ts` 的 control protocol 逐字段格式（阶段 4 前置）。
2. 从阶段 0 骨架开始，逐阶段交付、每阶段单测绿再进下一阶段。

**本计划尚未开始编码；请 review 范围/分阶段/技术决策，确认或调整后再动手。**
