# Claude Agent SDK for Java — 使用手册

Java 版 `@anthropic-ai/claude-agent-sdk`。通过 stream-json 协议在后台驱动 `claude` CLI，编程模型与 Node 版高度一致：响应式消息流、完整 `Options`、工具权限回调、hooks、进程内 MCP、运行时控制方法。

- **JDK 17+**，唯一依赖 `jackson-databind`；背压用 JDK 自带 `java.util.concurrent.Flow`，不引 Reactor。
- 各功能的**测试/验证状态**见 [`TESTING.md`](TESTING.md)；wire 协议细节见 [`wire-protocol.md`](wire-protocol.md)。

---

## 1. 前提

- JDK 17+ 与 Maven。
- `claude` CLI 在 `PATH` 上（或用 `Options.pathToClaudeCodeExecutable(...)` 指定）。
- 鉴权沿用 CLI 既有登录态（claude.ai 订阅或 `ANTHROPIC_API_KEY`）；SDK 不管鉴权。

## 2. 引入

本库当前未发布到中央仓库。本地构建安装：

```bash
cd ~/dev/java/claude-agent-sdk-java && mvn -DskipTests install
```

```xml
<dependency>
  <groupId>com.anthropic.claude</groupId>
  <artifactId>claude-agent-sdk-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

包结构：入口 `com.anthropic.claude.agent.*`；消息 `…message.*`；权限 `…permission.*`；hooks `…hooks.*`；MCP `…mcp.*`；elicitation `…elicitation.*`；自定义 agent `…agent.*`。

---

## 3. 快速开始（单轮）

```java
import com.anthropic.claude.agent.*;
import com.anthropic.claude.agent.message.*;

Query q = ClaudeAgent.query("用一句话总结 README.md", Options.builder()
        .model("claude-opus-4-7")
        .cwd("/path/to/project")
        .build());

for (SdkMessage m : q.messages()) {                 // 阻塞式便利迭代
    switch (m) {
        case SdkAssistantMessage a -> System.out.println(a.message().text());
        case SdkResultMessage r    -> System.out.println("花费 $" + r.totalCostUsd());
        default -> {}
    }
}
```

`ClaudeAgent.query(...)` 返回 `Query`，它**本身就是** `Flow.Publisher<SdkMessage>`。

## 4. 消费消息：两种方式

**A. 阻塞便利**（一把梭，无背压）：
```java
for (SdkMessage m : q.messages()) { ... }     // Iterable
q.stream().forEach(...);                        // Stream
List<SdkMessage> all = q.collect();             // 收齐
```

**B. 响应式订阅**（真背压，按 demand 拉取）：
```java
q.subscribe(new Flow.Subscriber<SdkMessage>() {
    Flow.Subscription s;
    public void onSubscribe(Flow.Subscription s) { this.s = s; s.request(1); }
    public void onNext(SdkMessage m) { handle(m); s.request(1); }   // 处理完再要下一条
    public void onError(Throwable t) { /* TransportException 等 */ }
    public void onComplete() { }
});
```
读线程在缓冲了 `Options.bufferCapacity()`（默认 1024）条未投递消息后阻塞，背压顺管道传到 `claude`（其 stdout 写阻塞）。**一个 Query 只能有一个订阅者**（用了 `messages()/stream()/collect()` 就不要再 `subscribe`）。

## 5. 消息类型（`SdkMessage` sealed 树）

用模式匹配穷尽消费。所有变体都带 `type()`、`raw()`（原始 JSON 逃生舱）、`sessionId()`、`uuid()`。

| 变体 | 关键内容 |
|---|---|
| `SdkAssistantMessage` | `message()`（`ApiMessage`：`text()` 拼接文本、`content()` 内容块列表、`model()`、`stopReason()`）、`requestId()`、`parentToolUseId()` |
| `SdkUserMessage` | `message()`（含 tool_result 块）、`parentToolUseId()` |
| `SdkResultMessage` | `subtype()`(`Subtype.SUCCESS/ERROR_*`)、`isError()`、`result()`、`totalCostUsd()`、`numTurns()`、`durationMs()`、`usage()`、`modelUsage()`、`structuredOutput()` |
| `SdkSystemMessage` | `subtype()`、`isInit()`、`init()`（typed：`model()/permissionMode()/tools()/mcpServers()/...`） |
| `SdkPartialAssistantMessage` | `eventType()`、`event()`（仅 `includePartialMessages(true)` 时有） |
| `SdkRateLimitMessage` | `rateLimitInfo()` |
| `SdkToolProgressMessage` / `SdkToolUseSummaryMessage` / `SdkAuthStatusMessage` / `SdkPromptSuggestionMessage` | 各自 typed 字段 |
| `SdkUnknownMessage` | 兜底：未知 type 不抛异常，`raw()` 取原始 JSON |

内容块 `ContentBlock`：`Text`、`Thinking`(thinking+signature)、`RedactedThinking`、`ToolUse`(id/name/input)、`ToolResult`(toolUseId/content/isError)、`Image`、`Unknown`。

> 注意：当前 CLI 的扩展思考多为加密(redacted)，`Thinking.thinking()` 可能为空、只有 `signature()`。

## 6. Options 全字段参考

`Options.builder()` 链式构建，`build()` 得到不可变对象。`ClaudeAgent.query` 不传 Options 用 `Options.defaults()`。

**进程 / 传输**
- `pathToClaudeCodeExecutable(String)` — claude 可执行路径（默认从 PATH 找）
- `cwd(String)` — 工作目录
- `env(String,String)` / `env(Map)` — 子进程环境变量
- `stderr(Consumer<String>)` — 逐行接收子进程 stderr
- `bufferCapacity(int)` — 背压缓冲条数（默认 1024）
- `extraArg(String flag, String value)` — 透传任意未建模 flag（value=null 即布尔 flag）

**模型 / 思考**
- `model(String)` / `fallbackModel(String)`
- `effort(String)` — low/medium/high/xhigh/max
- `thinking(ThinkingConfig)` — `ThinkingConfig.adaptive()` / `enabled(budget)` / `disabled()`（**推荐**）
- `maxThinkingTokens(int)` — 已废弃，等价 `thinking(enabled(n))`
- `betas(String...)`

**系统提示词**
- `systemPrompt(String)` — 替换默认提示词
- `appendSystemPrompt(String)` — 追加到默认提示词
- `systemPrompt(SystemPrompt)` — 类型化：`SystemPrompt.text(s)` 替换 / `SystemPrompt.preset(append, excludeDynamicSections)` 用默认 preset 并追加（优先于上面两个字符串字段）

**工具 / 权限**
- `allowedTools(String...)` / `disallowedTools(String...)` — 免审批 / 拒绝（如 `"Bash(git *)"`、`"Edit"`、`"mcp__srv__tool"`）
- `tools(String...)` — 限定可用的内置工具集（与 allowedTools 语义不同）
- `permissionMode(PermissionMode)` — DEFAULT/ACCEPT_EDITS/BYPASS_PERMISSIONS/PLAN/DONT_ASK/AUTO
- `canUseTool(CanUseTool)` — 工具审批回调（见 §7）
- `allowDangerouslySkipPermissions(boolean)`

**会话**
- `resume(String sessionId)` / `continueConversation(boolean)` / `forkSession(boolean)` / `sessionId(String)`
- `persistSession(boolean)` — false 加 `--no-session-persistence`
- `maxTurns(int)` / `maxBudgetUsd(double)`
- `settingSources(String...)`（user/project/local）/ `settings(String)`（文件路径或 JSON 串）
- `additionalDirectories(String...)`、`title(String)`

**MCP**
- `mcpServer(SdkMcpServer)` — 进程内 MCP（见 §9）
- `mcpServer(String name, McpServerConfig)` — 类型化外部 MCP（见 §10）
- `mcpConfig(String...)` — 原始 `--mcp-config` 文件/JSON 串
- `strictMcpConfig(boolean)`

**自定义 agent**
- `agent(String name)` — 选择 agent（`--agent`）
- `defineAgent(String name, AgentDefinition)` — 定义 agent（序列化进 `--agents` JSON）

**输出 / 调试 / 其它**
- `jsonSchema(String)` — 结构化输出 schema（结果在 `SdkResultMessage.structuredOutput()`）
- `includePartialMessages(boolean)` — 发 stream_event
- `includeHookEvents(boolean)`、`debug(boolean)`、`debugFile(String)`
- `plugin(PluginConfig)` — `PluginConfig.local(path)` → `--plugin-dir`
- `hook(...)`（见 §8）、`onElicitation(...)`（见 §11）

> `--print --input-format/--output-format stream-json --verbose` 是强制注入的，不用你管。

## 7. 工具权限回调 `canUseTool`

```java
Options.builder()
    .canUseTool((toolName, input, ctx) -> {
        if ("Bash".equals(toolName) && input.get("command").asText().startsWith("rm")) {
            return PermissionResult.deny("禁止 rm", /*interrupt*/ true);   // interrupt=true 中断整回合
        }
        return PermissionResult.allow();              // 不改入参；也可 allow(JsonNode 改写后的 input)
    })
    .build();
```
- 设了回调 → SDK 自动加 `--permission-prompt-tool stdio` +（未显式设 mode 时）`--permission-mode default`，让"ask"决策路由到回调。
- 回调在**后台线程**运行，可阻塞（如等人审批）不卡消息流。
- `ToolPermissionContext` 提供 `toolUseId()/agentId()/suggestions()/blockedPath()/decisionReason()/title()/...`。
- `PermissionResult.allow()` / `allow(updatedInput)` / `deny(msg)` / `deny(msg, interrupt)`。

## 8. Hooks

```java
import com.anthropic.claude.agent.hooks.*;

Options.builder()
    .hook(HookEvent.PRE_TOOL_USE, "Bash", (input, toolUseId) -> {
        if (looksDangerous(input)) return HookOutput.block("被策略拦截");
        return HookOutput.cont();
    })
    .hook(HookEvent.POST_TOOL_USE, (input, toolUseId) -> HookOutput.cont())   // 无 matcher
    .build();
```
- 30 个 `HookEvent`（PRE_TOOL_USE / POST_TOOL_USE / USER_PROMPT_SUBMIT / SESSION_START / STOP / …）。
- `HookOutput`：`cont()`（放行）/ `block(reason)` / `approve()` / `stop(reason)` / `async(timeoutSeconds)`；`withHookSpecificOutput(JsonNode)` 附事件专属输出。
- hook(event, matcher, timeout, callback) 的 **timeout 单位是秒**。
- 回调在后台线程运行；hook 注册通过 `initialize` 握手完成，触发走 `hook_callback`。

## 9. 进程内 MCP（在 JVM 里托管工具）

```java
import com.anthropic.claude.agent.mcp.*;
import com.fasterxml.jackson.databind.*;

JsonNode schema = new ObjectMapper().readTree(
    "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}");

SdkMcpServer calc = ClaudeAgent.createSdkMcpServer("calc",
    ClaudeAgent.tool("add", "两整数相加", schema, args ->
        McpToolResult.text(String.valueOf(args.get("a").asInt() + args.get("b").asInt()))));

Query q = ClaudeAgent.query("用 add 工具算 17+25", Options.builder()
        .mcpServer(calc)
        .allowedTools("mcp__calc__add")        // 工具名 = mcp__<server>__<tool>
        .build());
```
- SDK 内置最小 MCP JSON-RPC server（initialize/tools-list/tools-call），经 control 通道 `mcp_message` 与 claude 通信，**无需另起进程/socket**。
- `ToolHandler` 在后台线程运行；返回 `McpToolResult.text(s)` / `error(s)` / `of(contentArray, isError)`；抛异常自动转为 isError 结果。
- **此路径已对真 claude 验证**（见 TESTING.md L1）。

## 10. 外部 MCP（stdio / sse / http）

```java
import com.anthropic.claude.agent.mcp.McpServerConfig;

Options.builder()
    .mcpServer("fs", McpServerConfig.stdio("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
    .mcpServer("api", McpServerConfig.http("https://example.com/mcp"))   // 也有 .sse(url)
    .build();
```
序列化进 `--mcp-config '{"mcpServers":{...}}'`。需要 headers/timeout/env/alwaysLoad 时用对应 record 的全构造器（`new McpServerConfig.Http(url, headers, timeout, alwaysLoad)` 等）。

## 11. 多轮 / 流式输入

**命令式**（自己推进、自己结束）：
```java
Query q = ClaudeAgent.streamingQuery(options);   // 起进程但不发输入
q.streamInput("第一轮问题");
// ... 读消息 ...
q.streamInput("追问");
q.streamInput(UserMessageInput.blocks(contentBlocksJson).withParentToolUseId("tu1"));  // 结构化输入
q.endInput();                                     // 关 stdin → claude 收尾退出
```

**响应式**（从 Publisher 喂 string，完成时自动 endInput）：
```java
Query q = ClaudeAgent.query(somePublisherOfStrings, options);
```

## 12. 运行时控制方法（`Query`，均返回 `CompletableFuture`）

```java
q.interrupt();                                   // 中断当前回合
q.setPermissionMode(PermissionMode.ACCEPT_EDITS);
q.setModel("claude-opus-4-7");                   // null/"default" 恢复默认
q.setMaxThinkingTokens(8000);                    // null 清除，0 关闭
q.mcpServerStatus().thenAccept(list -> ...);     // List<McpServerStatus>
q.reconnectMcpServer("fs"); q.toggleMcpServer("fs", false);
q.setMcpServers(serversJson);  q.stopTask("task-1");  q.applyFlagSettings(settingsJson);
q.backgroundTasks("tu-id");  q.backgroundTasks();  // Ctrl+B 背景化任务，返回 CompletableFuture<Boolean>（需较新 CLI）
q.getContextUsage();  q.getSettings();           // 返回 raw JsonNode
InitializeResult init = q.initializationResult().get();   // models/commands/account/pid/...
q.supportedModels(); q.supportedCommands(); q.supportedAgents(); q.accountInfo();
```
> 注意：除 interrupt/setModel 等的封套已被真 claude（MCP 路径）证过外，**这些方法的真机响应未逐个端到端测试**（见 TESTING.md §2）。失败（CLI 回错误响应或通道关闭）会使 future 异常完成为 `ControlException`；进程已退出时调用立即失败。

## 13. Elicitation（MCP server 请求用户输入）

```java
import com.anthropic.claude.agent.elicitation.*;

Options.builder()
    .onElicitation(req -> {
        if ("url".equals(req.mode())) { openBrowser(req.url()); return ElicitationResult.accept(); }
        JsonNode form = collectForm(req.requestedSchema());
        return ElicitationResult.accept(form);     // 或 decline() / cancel()
    })
    .build();
```
未设回调或回调抛异常/返回 null → 默认 `cancel`。

## 14. 错误处理与生命周期

- **`TransportException`**：spawn 失败、管道 IO 错误、或 claude 非零退出（带 `exitCode()` + `stderrTail()`）。从 `onError`/`collect()` 抛出。
- **`ControlException`**：control 请求失败或通道关闭，控制方法 future 异常完成。
- **`SdkMessageParseException`**：解析非 JSON 行时抛（传输层会跳过并记日志，正常不外露）。
- 未知 `type` → `SdkUnknownMessage`（不抛）；未知字段宽松忽略。
- **`q.close()`**：终止子进程、释放线程、完成消息流。幂等。订阅者 cancel 也会触发 close。
- one-shot 模式：写完 prompt 后 stdin 保持打开（让工具审批 control_response 能回写），收到 `result` 才关 stdin。

## 15. 测试你的集成

SDK 自带测试件（`testkit` 包，作 test 依赖可复用思路）：
- `FakeTransport` — 内存 Transport，`feedLine/feedExit/feedError` 模拟 claude 侧，`written/endInputCalled` 断言 SDK 发出了什么。
- `MockClaude` + `MockClaudeLauncher` — 脚本化的真子进程 stand-in，经 `Options.pathToClaudeCodeExecutable` 注入，确定性地测整条 spawn/stdio 链路。
- `ClaudeAgent.query(prompt, options, transport)` / `streamingQuery(options, transport)` 接受显式 Transport 作注入点。

## 16. 常见坑

- **stream-json 强制 verbose**：已自动注入，别手动覆盖。
- **canUseTool 不生效**：确认设了回调（自动加 `--permission-prompt-tool stdio` + `--permission-mode default`）；CLI 只在规则判定为"ask"时才发 can_use_tool。
- **NDJSON 行安全**：U+2028/U+2029 写出时自动转义（claude 是 JS，按这俩字符切流）。
- **一个 Query 一个订阅者**：`messages()/collect()/stream()` 与 `subscribe()` 二选一。
- **skills/sandbox 暂不支持**：通道未对 CLI 核实，见 README「Known parity gaps」；急用可尝试 `extraArg`/`settings`。
