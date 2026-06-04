# 测试功能清单（验证状态）

> 这份清单**如实**区分"测试过确认没问题"和"没测过"。请按验证级别理解可信度。
> 全套：`mvn test`（94 个单测/mock 测试）+ `mvn test -Dclaude.sdk.it=true`（再加 2 个真 claude 集成测试）= **96 测试全绿**。

## 验证级别定义

| 级别 | 含义 | 可信度 |
|---|---|---|
| **L1 真 claude** | 跑真实 `claude` CLI 端到端验证 | 最高（对真实 CLI 行为成立） |
| **L2 真子进程** | 用脚本化的 `MockClaude` Java 子进程，走完整 spawn → stdio → 解析 → 退出链路（不是真 claude，但是真进程、真管道） | 高（传输/管道/退出时机真实） |
| **L3 单测** | 内存 `FakeTransport` 或纯单元，确定性、不起进程 | 中高（逻辑正确，但未碰真 IO/真 CLI） |
| **❌ 未测** | 代码已实现，但没有对应测试，或无法确定性测试 | 未知（仅"按源码/文档推断应当正确"） |
| **⛔ 未实现** | 故意 defer，没写代码 | — |

---

## 1. 已测试 · 确认没问题

### L1 — 真 claude CLI 端到端验证过

| 功能 | 测试 | 验证内容 |
|---|---|---|
| 单轮 `query(String)` | `RealClaudeSmokeTest` | 真 claude 收到 prompt → system init + assistant + result，进程干净退出，result 非错误 |
| **in-process MCP 工具调用** | `RealMcpSmokeTest` | 真 claude 接受我的 `initialize` 握手、声明 `sdkMcpServers`、经 `mcp_message` 调用 Java 侧 `add` 工具并拿到结果（**整条 control + MCP 链路对真 CLI 成立**） |

> 含义：control 通道（initialize 握手 + control_request/response 封套 + mcp_message JSON-RPC 往返）已被真 claude 实际走通——这是其它 control 功能可信度的基础。

### L2 — 真子进程（MockClaude）端到端验证过

| 功能 | 测试 | 验证内容 |
|---|---|---|
| mock-claude 子进程机制 | `MockClaudeSmokeTest` | 真进程吐 NDJSON、记录 argv/stdin、退出码 |
| 单轮 query 完整管道 | `QueryProcessE2ETest` | spawn → 写 user 消息 → 读 NDJSON → 解析 → result → 收到 3 条消息；**强制 flag**（`--print/--input-format/--output-format stream-json/--verbose/--model`）确实被传入 |
| 多轮流式输入 | `MultiTurnProcessE2ETest` | 真进程读 2 条 user 消息、按序回 2 条 result，顺序正确 |
| can_use_tool 审批往返 | `CanUseToolE2ETest` | 真进程发 can_use_tool → SDK 回调 → 经**未关闭的 stdin** 写回 allow 响应（`behavior/updatedInput/toolUseID/request_id` 都对）；`--permission-prompt-tool stdio` + `--permission-mode default` 确实被传入 |

### L3 — 内存 FakeTransport / 纯单测验证过

| 功能 | 测试 | 验证内容 |
|---|---|---|
| 消息解析（全 11 变体 + content blocks） | `SdkMessageParsingTest` | 用**真 claude 抓的 fixture** + 合成 fixture：assistant(text/thinking)/user/result(success+error 子类)/system(init typed)/stream_event/rate_limit/tool_progress/tool_use_summary/auth_status/prompt_suggestion；未知 type → Unknown 不抛；畸形 JSON 抛异常 |
| 背压（Reactive Streams） | `MessagePublisherTest` | demand 门控（request(n) 只发 n 条）、Semaphore 生产者背压（缓冲满则阻塞）、终止信号、单订阅者拒绝、非法 request(0)→onError、cancel |
| 行路由 | `QueryFakeTransportTest` | 正常流顺序+完成、one-shot 收到 result 才关 stdin、control 行被过滤不当消息、畸形/非对象行跳过、异常退出→onError、缓冲消息先于错误投递 |
| keep_alive / env 帧忽略 | `ParityGapsTest` | `keep_alive`/`update_environment_variables` 不作为消息投递 |
| Options → argv 映射 | `OptionsToCliArgsTest` | 强制 flag、标量/布尔/变参 flag、permission-mode、canUseTool→`--permission-prompt-tool stdio`+默认 mode、maxTurns/maxThinkingTokens、thinking 三态、tools/jsonSchema、agents JSON（含 maxTurns/permissionMode/skills）、extraArg 逃生舱 |
| control 协议双向 | `ControlProtocolTest` | 发请求封套正确、成功/空 payload/错误响应、camelCase requestId 容错（收发两向）、reject-pending、can_use_tool allow/deny/interrupt/updatedInput/toolUseID 覆盖/dedup、elicitation accept/cancel |
| Query 运行时控制方法 | `QueryControlMethodsTest` | interrupt/setPermissionMode/setModel/setMaxThinkingTokens 发出正确请求并 resolve、mcpServerStatus 解析、initializationResult 解析+缓存只发一次、reconnect/toggle/stopTask/applyFlagSettings/getSettings/**backgroundTasks**（解析 `backgrounded ?? true`）字段正确、终止后快速失败 |
| hooks | `HooksTest` | 30 个 HookEvent 名;hook 注册进 initialize（matcher+hookCallbackIds）;hook_callback 分发回调并回 HookOutput（block/async）;未知 callback_id 回空;无 hook 时不发 initialize |
| in-process MCP JSON-RPC server | `InProcessMcpServerTest` | initialize（回 serverInfo + 回显 protocolVersion）/tools-list（带 schema）/tools-call（调 handler）/未知工具→isError/ping/notification→null/未知方法→-32601/handler 抛异常→isError |
| in-process MCP 全链路 | `McpRoundTripTest` | initialize 声明 sdkMcpServers → mcp_message tools/call → handler 执行 → mcp_response 回流;未知 server → error |
| 流式输入 | `StreamingInputTest` | 命令式 streamInput 按序写 + endInput;响应式 `Publisher<String>` 按序写 + 完成时关 stdin |
| parity 补全项 | `ParityGapsTest` | structured_output 访问器;systemPrompt preset 映射;plugins→`--plugin-dir`;McpServerConfig 四态→`--mcp-config` JSON;结构化 streamInput(content blocks + parent_tool_use_id) |

---

## 2. ❌ 已实现但**没有**对真 claude 测试（仅 mock/单测/源码核对）

> 这些功能的 **wire 格式来自反编译 CLI 源码 + 官方 npm 包 `sdk.d.ts` 逐字段核对**，且 control 通道本身已被真 claude（MCP 路径）走通，但**下列具体流程没有在真 claude 上端到端跑过**（多数难以确定性触发）。

| 功能 | 现状 | 为什么没测真 claude |
|---|---|---|
| `canUseTool` / hooks 对真 claude | 仅 L2 mock + L3 单测 | 要真 claude 产生"ask"决策 / 触发 hook 才行，难做成确定性测试 |
| Query 控制方法（interrupt/setModel/setPermissionMode/setMaxThinkingTokens/mcpServerStatus/getContextUsage/initializationResult/reconnect/toggle/setMcpServers/stopTask/applyFlagSettings/getSettings）对真 claude | 仅 L3 FakeTransport | 请求封套已在真 claude（MCP 路径）证过，但每个方法的真机响应未逐个跑 |
| 外部 MCP（stdio/sse/http 经 `--mcp-config`） | argv JSON 已测（L3） | 没拿真实外部 MCP server 对真 claude spawn |
| `onElicitation` 对真 claude | 仅 L3（accept/cancel） | 要真 MCP server 发起 elicitation 才能触发 |
| `thinking`/`tools`/`jsonSchema`/`agents`/`plugins`/`resume`/`continue`/`forkSession`/`sessionId`/`maxTurns`/`maxBudgetUsd` 等 flag 的**真机效果** | argv 映射已测（L3） | 只验证了"传了正确的 flag"，没验证"claude 是否按预期表现" |
| `includePartialMessages` → stream_event | 合成 fixture 解析过（L3） | 没抓真 claude 的 partial 流来解析 |
| result 错误子类（error_max_turns 等） | 合成 fixture 解析过（L3） | 没在真 claude 上触发出错误结果 |
| 进程异常退出 → TransportException + stderr | L3 FakeTransport 模拟 | 没在真 claude 崩溃场景验证 stderr tail 抓取 |

---

## 3. ⛔ 未实现（故意 defer，详见 README「Known parity gaps」）

| 功能 | 原因 |
|---|---|
| `skills` / `sandbox` 选项 | 无 CLI flag;CLI initialize schema 里 `skills` 是遥测对象、`sandbox` 不存在 → **通道无法对 CLI 核实**，不发未验证代码 |
| 响应式结构化输入 `Publisher<UserMessageInput>` | 与 `Publisher<String>` 重载**泛型擦除冲突**;结构化输入走命令式 `streamInput(UserMessageInput)` |
| MCP `tool` 的 annotations/_meta/searchHint/alwaysLoad、server instructions | 次要元数据 |
| `systemPrompt: string[]`（缓存边界）形态 | 次要 |
| `setMcpServers` 的类型化 config 入参 | 当前接受 raw `JsonNode` |

---

## 4. 复现命令

```bash
cd ~/dev/java/claude-agent-sdk-java
mvn test                          # L2/L3：94 个，不需要真 claude
mvn test -Dclaude.sdk.it=true     # 追加 L1：RealClaudeSmokeTest + RealMcpSmokeTest（需登录的 claude，消耗额度）
mvn test -Dtest=RealMcpSmokeTest -Dclaude.sdk.it=true   # 只跑真 claude 的 MCP 验证
```
