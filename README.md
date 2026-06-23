# Claude Agent SDK for Java

A faithful Java port of the Node [`@anthropic-ai/claude-agent-sdk`](https://docs.claude.com). It
drives the `claude` CLI headlessly over the bidirectional **stream-json** protocol, exposing a
near-identical programming model: a reactive message stream, the full `Options` surface, tool
permission callbacks, hooks, in-process MCP servers, and runtime control methods.

- **JDK 17+**, single dependency: `jackson-databind`. No Spring / Reactor — backpressure is built on
  `java.util.concurrent.Flow`.
- Wire format reverse-engineered from `claude` v2.1.159 and validated end-to-end against the real CLI
  (including a live in-process MCP tool call).

**文档**：完整使用手册 [`docs/USAGE.md`](docs/USAGE.md)；功能测试/验证状态清单 [`docs/TESTING.md`](docs/TESTING.md)；wire 协议规格 [`docs/wire-protocol.md`](docs/wire-protocol.md)。

## Requirements

- JDK 17+ and Maven.
- The `claude` CLI on `PATH` (or set `Options.pathToClaudeCodeExecutable`).
- Authentication is whatever the CLI already uses (claude.ai subscription or `ANTHROPIC_API_KEY`);
  the SDK does not manage auth.

## Quick start — one-shot

```java
import com.anthropic.claude.agent.*;
import com.anthropic.claude.agent.message.*;

Query q = ClaudeAgent.query("Summarize README.md in one sentence", Options.builder()
        .model("claude-opus-4-7")
        .cwd("/path/to/project")
        .build());

for (SdkMessage m : q.messages()) {            // blocking convenience iterator
    if (m instanceof SdkAssistantMessage a) {
        System.out.println(a.message().text());
    } else if (m instanceof SdkResultMessage r) {
        System.out.println("cost=$" + r.totalCostUsd());
    }
}
```

`ClaudeAgent.query(...)` returns a `Query`, which **is** a `Flow.Publisher<SdkMessage>`. Consume it
reactively (with real backpressure) via `subscribe(...)`, or use the blocking conveniences
`messages()` / `stream()` / `collect()`.

## Reactive consumption (backpressure)

```java
q.subscribe(new Flow.Subscriber<SdkMessage>() {
    private Flow.Subscription s;
    public void onSubscribe(Flow.Subscription s) { this.s = s; s.request(1); }
    public void onNext(SdkMessage m) { handle(m); s.request(1); }   // demand-driven
    public void onError(Throwable t) { ... }
    public void onComplete() { ... }
});
```

The transport reader blocks once `Options.bufferCapacity()` messages are buffered-but-undelivered,
propagating backpressure down the pipe to `claude`.

## Multi-turn (streaming input)

```java
// Imperative
Query q = ClaudeAgent.streamingQuery(options);
q.streamInput("first question");
// ... read messages ...
q.streamInput("follow-up");
q.endInput();                       // close stdin → the CLI finishes and exits

// Reactive: feed turns from a publisher (stdin closes on completion)
Query q2 = ClaudeAgent.query(somePublisherOfStrings, options);
```

## Examples

End-to-end, runnable examples of the two ways to drive the CLI from a long-lived service live in
[`examples/`](examples/) (desensitized distillations of a production agent runtime):

- [`SingleTurnExample.java`](examples/SingleTurnExample.java) — **no process reuse**: one subprocess
  per turn, conversation state carried by the CLI session file (`resume`).
- [`SessionPoolExample.java`](examples/SessionPoolExample.java) — **process-reuse pool**: keep the
  subprocess warm (`streamingQuery`) and feed turns via `streamInput`, with epoch-tagged message
  routing, stale-spawn respawn, close-on-anomaly, and a capacity gate.

See [`examples/README.md`](examples/README.md) for the trade-offs and how to compile/run them.

## Tool permission callback (`canUseTool`)

```java
Options.builder()
    .canUseTool((toolName, input, ctx) -> {
        if ("Bash".equals(toolName) && input.get("command").asText().startsWith("rm")) {
            return PermissionResult.deny("destructive command blocked", /*interrupt*/ true);
        }
        return PermissionResult.allow();           // updatedInput defaults to {} (use original)
    })
    .build();
```

Providing a callback makes the SDK launch `claude` with `--permission-prompt-tool stdio` and
(unless you set one) `--permission-mode default`, so "ask" decisions route to your callback. The
callback runs on a background thread and may block (e.g. awaiting human approval).

## Hooks

```java
Options.builder()
    .hook(HookEvent.PRE_TOOL_USE, "Bash", (input, toolUseId) -> {
        if (suspicious(input)) return HookOutput.block("blocked by policy");
        return HookOutput.cont();
    })
    .build();
```

Hooks are registered in the `initialize` handshake and invoked via `hook_callback` control requests.

## In-process MCP tools

Host MCP tools inside your JVM — no separate process or socket:

```java
SdkMcpServer calc = ClaudeAgent.createSdkMcpServer("calc",
    ClaudeAgent.tool("add", "Add two integers", inputSchemaJsonNode, args ->
        McpToolResult.text(String.valueOf(args.get("a").asInt() + args.get("b").asInt()))));

Options.builder()
    .mcpServer(calc)
    .allowedTools("mcp__calc__add")
    .build();
```

The SDK implements a minimal MCP JSON-RPC server (`initialize` / `tools/list` / `tools/call`) and
serves it over the control channel's `mcp_message` transport.

## Runtime control

`Query` exposes the control methods (all return `CompletableFuture`):

```java
q.interrupt();
q.setPermissionMode(PermissionMode.ACCEPT_EDITS);
q.setModel("claude-opus-4-7");
q.setMaxThinkingTokens(8000);                 // null clears, 0 disables
q.mcpServerStatus().thenAccept(...);
InitializeResult init = q.initializationResult().get();   // models, commands, account, ...
q.supportedModels(); q.supportedCommands(); q.accountInfo();
```

## Message types

`SdkMessage` is a sealed interface — pattern-match exhaustively:

`SdkAssistantMessage`, `SdkUserMessage`, `SdkResultMessage` (with `Subtype`: success / error_*),
`SdkSystemMessage` (typed `init()` view), `SdkPartialAssistantMessage` (stream_event, requires
`includePartialMessages`), `SdkRateLimitMessage`, `SdkToolProgressMessage`,
`SdkToolUseSummaryMessage`, `SdkAuthStatusMessage`, `SdkPromptSuggestionMessage`, and
`SdkUnknownMessage` (drift-tolerant fallback — an unrecognized wire `type` never throws).

## Building & testing

```bash
mvn test                                       # unit + mock-subprocess tests
mvn test -Dclaude.sdk.it=true                  # also run the real-claude smoke tests
```

Tests use an in-memory `FakeTransport` for deterministic protocol coverage and a real subprocess
`MockClaude` (a scriptable stand-in) to exercise the full spawn → stdio → parse pipeline. Real-CLI
smoke tests are guarded by `-Dclaude.sdk.it=true`.

## Design notes

- **No FIFO** — direct parent/child stdio pipes; the child dies with the parent. Exit is detected
  via stdout EOF + `Process.waitFor()`.
- **One serialized writer** — all stdin writes go through a single-thread executor (input messages
  and control responses never interleave).
- **Lenient parsing** — `FAIL_ON_UNKNOWN_PROPERTIES=false`; unknown message types and control
  subtypes are tolerated, not fatal.
- **NDJSON safety** — U+2028 / U+2029 are escaped on write (the JS CLI splits the stream on them).
- The exact wire protocol (envelopes, control subtypes, field-name quirks) is documented in
  [`docs/wire-protocol.md`](docs/wire-protocol.md).

## Status / parity

Cross-checked against the published `@anthropic-ai/claude-agent-sdk` **v0.3.160** type declarations
(`sdk.d.ts`) and its actual argv construction.

**Implemented**: `query` (string + reactive), `Options` → CLI flag mapping (incl. hidden flags
`--max-turns` / `--max-thinking-tokens` / `--thinking` / `--tools` / `--json-schema`), `thinking`
(`ThinkingConfig`), the `SdkMessage` tree (11 variants + drift-tolerant fallback), `canUseTool`
(incl. `toolUseId` override), hooks (all 30 `HookEvent`s, sync + async `HookOutput`, seconds timeout),
in-process MCP, custom agents (`AgentDefinition` with model/tools/maxTurns/permissionMode/skills/…),
and the runtime control methods (interrupt / setModel / setPermissionMode / setMaxThinkingTokens /
mcpServerStatus / reconnectMcpServer / toggleMcpServer / setMcpServers / stopTask / applyFlagSettings /
getSettings / getContextUsage / initializationResult / supported*).

Also implemented (second cross-check pass): typed external MCP config (`McpServerConfig` stdio/sse/http
→ `--mcp-config`), `systemPrompt` preset form (`SystemPrompt.preset(append, excludeDynamicSections)`),
structured streaming input (`streamInput(UserMessageInput)` with content blocks / parent_tool_use_id),
`result.structuredOutput()`, `plugins` (`--plugin-dir`), and `onElicitation` (MCP elicitation handling).

A third pass (multi-agent cross-check vs `sdk.d.ts` + the CLI's real flags) additionally added/fixed:
`ThinkingConfig.display` → `--thinking-display`, `AgentDefinition` effort/background/memory, a bounded
inbound-id dedup set (matches the CLI's 1000-entry cap), and `Query.backgroundTasks(toolUseId?)`
(parses `response.backgrounded ?? true`, matching the SDK).

**Known parity gaps** (deliberately deferred; honest about why):
- `skills` and `sandbox` options — their delivery channel could not be confirmed against the CLI
  (no flag; the CLI's initialize schema exposes `skills` only as telemetry, and `sandbox` not at all),
  so they are not shipped rather than guessed.
- Reactive structured input (`Flow.Publisher<UserMessageInput>`) — omitted because it would clash by
  erasure with the existing `Flow.Publisher<String>` overload; structured input is available
  imperatively via `streamInput(UserMessageInput)`.
- `permissionPromptToolName` (custom MCP permission tool), agent-level `mcpServers`, disabling all
  tools via `tools("")`, the `document` content block, minor MCP metadata
  (`tool` annotations / `_meta` / `searchHint` / `alwaysLoad`, server `instructions`), and the
  `systemPrompt: string[]` cache-boundary form.
- `HookEvent` models all 30 events from `sdk.d.ts` v0.3.160; the CLI v2.1.159 schema only validates
  27 — registering one of the 3 newest (`PostToolBatch`/`UserPromptExpansion`/`MessageDisplay`)
  against an older CLI may be rejected. This matches the SDK type, not the older CLI.

Unmodeled message fields remain accessible via each message's `raw()` JSON node; any unmodeled flag
via `Options.builder().extraArg(...)`.
