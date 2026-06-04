# Claude Agent SDK Wire Protocol 参考规格

> 目标：在 Java 里 1:1 复刻 `@anthropic-ai/claude-agent-sdk`。本文档从反编译的 **Claude Code v2.1.159** TypeScript 源码逐字段还原 SDK 与 `claude` CLI 进程之间的 wire protocol。
>
> 源码根目录：`/Users/songxinjian/dev/java/claude-code-source/src/`
>
> **可靠性说明**：本文档中的 JSON 字段名全部来自 zod schema 的 `z.literal(...)` / `z.object({...})` key 字面量，以及接收端 `print.ts` / `structuredIO.ts` 里的字符串字面量。这些字面量在反编译/混淆后**不会改变**，是最可靠的还原线索。变量名（如 `message`、`request`）可能被混淆，但 JSON key 不会。
>
> **重要前提**：本源码树是 **CLI 端**（被 SDK spawn 的那个 `claude` 进程）。SDK npm 包本体（`query()` 实现、`ProcessTransport`、`Options` / `runtimeTypes` / `controlTypes` 的实现）**不在**这棵树里——CLI 端只保留了类型声明的占位符（见 `entrypoints/agentSdkTypes.ts`，所有函数体都是 `throw new Error('not implemented')`）。因此：
> - **wire 字段**（NDJSON 封套、control 协议）→ 完全可信，来自 `controlSchemas.ts` + `coreSchemas.ts` + CLI 接收端实现。
> - **SDK 如何拼命令行参数** → 由 CLI 端的 commander Option 定义（`main.tsx`）和校验逻辑反推，标注为"反推"。

---

## 0. 总览：两条通道复用同一对 stdio

SDK 通过子进程方式 spawn `claude`，与它之间是 **stdin/stdout 上的 NDJSON（newline-delimited JSON）双向流**。这一条流上**复用**了三类消息：

| 方向 | 消息族 | `type` 顶层取值 |
|---|---|---|
| CLI → SDK (stdout) | SDK 消息（对话内容） | `assistant` / `user` / `result` / `system` / `stream_event` / `tool_progress` / `auth_status` / `rate_limit_event` / `tool_use_summary` / `prompt_suggestion` … |
| CLI → SDK (stdout) | control 请求（CLI 主动发起，要 SDK 回应） | `control_request` |
| CLI → SDK (stdout) | control 响应（回应 SDK 之前发的请求） | `control_response` |
| CLI → SDK (stdout) | control 取消 | `control_cancel_request` |
| CLI → SDK (stdout) | 保活 | `keep_alive` |
| SDK → CLI (stdin) | 用户消息（喂给模型的 prompt） | `user` |
| SDK → CLI (stdin) | control 请求（SDK 主动发起，要 CLI 回应） | `control_request` |
| SDK → CLI (stdin) | control 响应（回应 CLI 发的 can_use_tool / hook_callback / mcp_message / elicitation） | `control_response` |
| SDK → CLI (stdin) | 保活 | `keep_alive` |
| SDK → CLI (stdin) | 运行时改环境变量 | `update_environment_variables` |

权威的 union 定义在 `controlSchemas.ts:642-663`：

```ts
// entrypoints/sdk/controlSchemas.ts:642
export const StdoutMessageSchema = lazySchema(() =>
  z.union([
    SDKMessageSchema(),                          // 对话消息（见 §B）
    SDKStreamlinedTextMessageSchema(),
    SDKStreamlinedToolUseSummaryMessageSchema(),
    SDKPostTurnSummaryMessageSchema(),
    SDKControlResponseSchema(),                   // {type:'control_response', ...}
    SDKControlRequestSchema(),                    // {type:'control_request', ...}
    SDKControlCancelRequestSchema(),              // {type:'control_cancel_request', ...}
    SDKKeepAliveMessageSchema(),                  // {type:'keep_alive'}
  ]),
)

// entrypoints/sdk/controlSchemas.ts:655
export const StdinMessageSchema = lazySchema(() =>
  z.union([
    SDKUserMessageSchema(),                       // {type:'user', ...}
    SDKControlRequestSchema(),
    SDKControlResponseSchema(),
    SDKKeepAliveMessageSchema(),
    SDKUpdateEnvironmentVariablesMessageSchema(), // {type:'update_environment_variables', variables:{...}}
  ]),
)
```

**关键不对称**：`control_request` 双向都可能出现。
- SDK → CLI 的 control_request：`initialize` / `interrupt` / `set_permission_mode` / `set_model` / `set_max_thinking_tokens` / `mcp_status` / `get_context_usage` / `mcp_message` / `rewind_files` / `cancel_async_message` / `seed_read_state` / `mcp_set_servers` / `reload_plugins` / `mcp_reconnect` / `mcp_toggle` / `stop_task` / `apply_flag_settings` / `get_settings` / `elicitation`。
- CLI → SDK 的 control_request：`can_use_tool` / `hook_callback` / `mcp_message`（反向，调 SDK in-process MCP）/ `elicitation`。即"CLI 需要 SDK 帮忙做决策"的场景。

每条消息一行，行尾 `\n`。写出时用 `ndjsonSafeStringify`（见 §E）。

---

## A. 进程启动

### A.1 必加 / 关键命令行 flag（反推自 CLI 端 Option 定义与校验）

SDK 以 stream-json 双向模式驱动 `claude`，对应一组**强约束的 flag 组合**。来源 `main.tsx:976`（commander 的 `.addOption(...)` 链）和 `main.tsx:1818-1859`、`cli/print.ts:787` 的校验。

| flag | 取值 | 说明 / 由哪个 Options 字段决定 |
|---|---|---|
| `--print` / `-p` | （布尔，无值） | **必加**。进入非交互（headless）模式。SDK 总是 headless。`main.tsx`：`-p, --print` |
| `--input-format stream-json` | 固定 `stream-json` | **必加**。否则 stdin 只能收纯文本。`choices(['text','stream-json'])` |
| `--output-format stream-json` | 固定 `stream-json` | **必加**。`choices(['text','json','stream-json'])` |
| `--verbose` | （布尔） | **必加**（当 output-format=stream-json 时）。见 §E.1 硬约束 |
| `--permission-mode <mode>` | `default`/`acceptEdits`/`bypassPermissions`/`plan`/`dontAsk` | 由 Options.permissionMode 决定；也可后续用 `set_permission_mode` control 改 |
| `--dangerously-skip-permissions` | （布尔） | 对应 bypassPermissions；启用后才允许运行时切到 bypassPermissions（见 §C set_permission_mode 校验） |
| `--allow-dangerously-skip-permissions` | （布尔） | 允许把 bypass 作为"可选项"开启，但不默认启用 |
| `--include-partial-messages` | （布尔） | 由 Options.includePartialMessages 决定。开启后 stdout 才发 `stream_event` 增量。**要求 `--print` + `--output-format=stream-json`**（`main.tsx:1848`） |
| `--include-hook-events` | （布尔） | 开启后 stdout 才发全部 hook 生命周期事件（`hook_started`/`hook_progress`/`hook_response`）。默认只发 `SessionStart`/`Setup`（`main.tsx:1230`） |
| `--replay-user-messages` | （布尔） | 把 stdin 收到的 user 消息回显到 stdout 用于 ack。**要求双向都是 stream-json**（`main.tsx:1840`） |
| `--permission-prompt-tool <tool>` | MCP 工具名 | 仅 `--print` 下生效。指定一个 MCP 工具来处理权限提示（与 canUseTool 是两条不同的路） |
| `--mcp-config <configs...>` | JSON 文件/字符串 | 外部 MCP 服务器配置 |
| `--system-prompt <prompt>` / `--system-prompt-file <file>` | 字符串/路径 | 也可改走 initialize control（避免 ARG_MAX，见 §C） |
| `--append-system-prompt <prompt>` / `--append-system-prompt-file <file>` | 字符串/路径 | 同上 |
| `--allowedTools` / `--allowed-tools <tools...>` | 逗号/空格分隔 | 如 `"Bash(git:*) Edit"` |
| `--disallowedTools` / `--disallowed-tools <tools...>` | 逗号/空格分隔 | |
| `--tools <tools...>` | `""`/`default`/名字列表 | 限定可用内置工具集 |
| `--agents <json>` | JSON 对象 | 自定义 subagent；也可改走 initialize control（避免 ARG_MAX） |
| `--model <model>` | 字符串/alias | 主模型。`main.tsx:993` 确认：可传 alias（`sonnet`/`opus`）或全名（`claude-sonnet-4-6`）；`default` 表示默认主模型（`main.tsx:2019`）。也可后续走 `set_model` control |
| `--effort <level>` | `low`/`medium`/`high`/`max` | 推理 effort 等级（`main.tsx:993`） |
| `--max-turns <n>` | 数字 | 仅 `--print` 下生效 |
| `--max-budget-usd <amount>` | 数字 | 仅 `--print` 下生效；超预算返回 `error_max_budget_usd` |
| `--max-thinking-tokens <n>` | 数字 | 已废弃，建议用 `--thinking`；也可走 `set_max_thinking_tokens` control |
| `--thinking <mode>` | `enabled`/`adaptive`/`disabled` | 思考模式 |
| `--json-schema <schema>` | JSON Schema 字符串 | 结构化输出校验；也可走 initialize control 的 `jsonSchema` 字段 |
| `--resume [sessionId]` / `-r` | sessionId | 续接会话 |
| `--continue` / `-c` | （布尔） | 续接当前目录最近一次会话 |
| `--fork-session` | （布尔） | 续接时分叉出新 sessionId |
| `--session-id <uuid>` | UUID | 指定会话 ID（必须合法 UUID） |
| `--no-session-persistence` | （布尔） | 仅 `--print` 下生效；会话不落盘、不可 resume |
| `--add-dir <dirs...>` | 目录列表 | 额外允许工具访问的目录 |
| `--settings <file-or-json>` | 路径/JSON | 额外 settings |
| `--setting-sources <sources>` | `user,project,local` 逗号分隔 | 限定加载哪些 settings 源 |
| `--strict-mcp-config` | （布尔） | 只用 `--mcp-config` 的 MCP，忽略其它来源 |
| `--fallback-model <model>` | 字符串 | 主模型过载时自动回退 |
| `--betas <betas...>` | beta header 列表 | 仅 API key 用户 |
| `--agent <agent>` | agent 名 | 当前会话的主线程 agent |

> 注意：`--sdk-url <ws-url>`（用 WebSocket 而非 stdio 当 transport）在 v2.1.121 起被私有化白名单，第三方不可用。Java 复刻应走 **stdio ProcessTransport**，不要尝试 `--sdk-url`。

### A.2 stdin/stdout = NDJSON 双向通道

确认：是的。
- **写出**（CLI → SDK）：`structuredIO.ts:465`
  ```ts
  async write(message: StdoutMessage): Promise<void> {
    writeToStdout(ndjsonSafeStringify(message) + '\n')
  }
  ```
- **读入**（SDK → CLI，CLI 这一侧解析 stdin）：`structuredIO.ts:215` 的 `read()` 按 `\n` 切行，逐行 `jsonParse`。SDK 侧反过来即可。

每条消息严格一行 JSON + `\n`。空行被忽略（`structuredIO.ts:337`：`if (!line) return undefined`）。

---

## B. NDJSON 消息封套（stdout 方向：claude → SDK）

权威 union 在 `coreSchemas.ts:1854` 的 `SDKMessageSchema`。所有变体顶层都有 `type` 判别字段。

### B.0 公共字段约定

大多数 stdout SDK 消息都带：
- `uuid: string`（消息唯一 ID，UUID）
- `session_id: string`（会话 ID）

`assistant` / `user` 还带 `parent_tool_use_id: string | null`（subagent 工具调用产生的消息会指向父 tool_use_id；主线程为 `null`）。

### B.1 `type: "assistant"` — 助手消息

`coreSchemas.ts:1347` `SDKAssistantMessageSchema`：

| 字段 | 类型 | 可选 | 说明 |
|---|---|---|---|
| `type` | `"assistant"` | 否 | |
| `message` | Anthropic APIAssistantMessage | 否 | **占位符 `z.unknown()`**（`coreSchemas.ts:1241`），实际是 Anthropic Messages API 的 assistant `Message` 对象（见 §B.10） |
| `parent_tool_use_id` | `string \| null` | 否 | |
| `error` | enum | 是 | 见下方 error enum |
| `uuid` | UUID | 否 | |
| `session_id` | string | 否 | |

`error` 取值（`coreSchemas.ts:1256` `SDKAssistantMessageErrorSchema`）：
`authentication_failed` / `billing_error` / `rate_limit` / `invalid_request` / `server_error` / `unknown` / `max_output_tokens`

示例：
```json
{"type":"assistant","uuid":"a1b2...","session_id":"s-123","parent_tool_use_id":null,"message":{"id":"msg_01...","type":"message","role":"assistant","model":"claude-opus-4-...","content":[{"type":"text","text":"好的，我来看一下。"}],"stop_reason":null,"usage":{"input_tokens":120,"output_tokens":15}}}
```

### B.2 `type: "user"` — 用户消息

`coreSchemas.ts:1290` `SDKUserMessageSchema`（= `SDKUserMessageContentSchema` + `uuid?` + `session_id?`）：

| 字段 | 类型 | 可选 | 说明 |
|---|---|---|---|
| `type` | `"user"` | 否 | |
| `message` | Anthropic APIUserMessage | 否 | 占位符 `z.unknown()`，实际是 `{role:"user", content: string \| ContentBlock[]}` |
| `parent_tool_use_id` | `string \| null` | 否 | |
| `isSynthetic` | boolean | 是 | 合成消息（非真实用户输入） |
| `tool_use_result` | unknown | 是 | |
| `priority` | `"now"`/`"next"`/`"later"` | 是 | 命令队列优先级 |
| `timestamp` | string | 是 | ISO 时间戳，发起进程创建时间 |
| `uuid` | UUID | 是 | stdin 方向可省略 |
| `session_id` | string | 是 | stdin 方向可省略 |

**SDK → CLI 发用户消息**的最小形态（`structuredIO.ts:204` `prependUserMessage`）：
```json
{"type":"user","session_id":"","message":{"role":"user","content":"你好"},"parent_tool_use_id":null}
```
> 注意：发给 CLI 时 `session_id` 可填 `""`，`uuid` 可省。CLI 端 `processLine` 会校验 `message.message.role === 'user'`（`structuredIO.ts:451`），否则 `process.exit(1)`。

还有一个 **replay** 变体 `SDKUserMessageReplaySchema`（`coreSchemas.ts:1297`）：在 `SDKUserMessageContent` 基础上 `uuid`/`session_id` 必填，且多一个 `isReplay: true`。开了 `--replay-user-messages` 时 CLI 会把 stdin 收到的 user 消息回显到 stdout，带 `isReplay:true`。

### B.3 `type: "result"` — 回合结束结果

union（`coreSchemas.ts:1453` `SDKResultMessageSchema`）：成功 + 错误两种，靠 `subtype` 区分。

**成功** `SDKResultSuccessSchema`（`coreSchemas.ts:1407`）：`subtype: "success"`

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `"result"` | |
| `subtype` | `"success"` | |
| `duration_ms` | number | |
| `duration_api_ms` | number | |
| `is_error` | boolean | |
| `num_turns` | number | |
| `result` | string | 最终文本结果 |
| `stop_reason` | `string \| null` | |
| `total_cost_usd` | number | |
| `usage` | NonNullableUsage | 占位符 `z.unknown()`，实际是 Anthropic Usage |
| `modelUsage` | `Record<string, ModelUsage>` | 见 §B.11 |
| `permission_denials` | `SDKPermissionDenial[]` | 见下 |
| `structured_output` | unknown（可选） | 当用了 `--json-schema` |
| `fast_mode_state` | enum（可选） | `off`/`cooldown`/`on` |
| `uuid` | UUID | |
| `session_id` | string | |

**错误** `SDKResultErrorSchema`（`coreSchemas.ts:1428`）：`subtype` 取值之一：
`error_during_execution` / `error_max_turns` / `error_max_budget_usd` / `error_max_structured_output_retries`

字段同上，但**没有** `result` 与 `structured_output`，**多了** `errors: string[]`。

`SDKPermissionDenial`（`coreSchemas.ts:1399`）：`{ tool_name: string, tool_use_id: string, tool_input: Record<string,unknown> }`

`result.subtype` 全部取值汇总：`success` / `error_during_execution` / `error_max_turns` / `error_max_budget_usd` / `error_max_structured_output_retries`。

示例：
```json
{"type":"result","subtype":"success","duration_ms":4210,"duration_api_ms":3980,"is_error":false,"num_turns":2,"result":"任务完成。","stop_reason":"end_turn","total_cost_usd":0.0123,"usage":{"input_tokens":1500,"output_tokens":230},"modelUsage":{"claude-opus-4-...":{"inputTokens":1500,"outputTokens":230,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0.0123,"contextWindow":200000,"maxOutputTokens":32000}},"permission_denials":[],"uuid":"r-1","session_id":"s-123"}
```

### B.4 `type: "system"` — 系统消息（多 subtype）

`system` 是一族，靠 `subtype` 区分。全部 subtype 与各自字段如下。

**`subtype: "init"`** `SDKSystemMessageSchema`（`coreSchemas.ts:1457`）—— 每个会话开头发一次：

| 字段 | 类型 | 可选 |
|---|---|---|
| `type` | `"system"` | |
| `subtype` | `"init"` | |
| `agents` | `string[]` | 是 |
| `apiKeySource` | enum `user`/`project`/`org`/`temporary`/`oauth` | |
| `betas` | `string[]` | 是 |
| `claude_code_version` | string | |
| `cwd` | string | |
| `tools` | `string[]` | |
| `mcp_servers` | `Array<{name:string, status:string}>` | |
| `model` | string | |
| `permissionMode` | enum（见 §C PermissionMode） | |
| `slash_commands` | `string[]` | |
| `output_style` | string | |
| `skills` | `string[]` | |
| `plugins` | `Array<{name:string, path:string, source?:string}>` | |
| `fast_mode_state` | enum | 是 |
| `uuid` / `session_id` | | |

**`subtype: "compact_boundary"`** `SDKCompactBoundaryMessageSchema`（`coreSchemas.ts:1506`）：
- `compact_metadata: { trigger: "manual"\|"auto", pre_tokens: number, preserved_segment?: { head_uuid, anchor_uuid, tail_uuid } }`

**`subtype: "status"`** `SDKStatusMessageSchema`（`coreSchemas.ts:1533`）：
- `status: "compacting" \| null`，`permissionMode?`（enum）

**`subtype: "post_turn_summary"`** `SDKPostTurnSummaryMessageSchema`（`coreSchemas.ts:1544`，@internal）：
- `summarizes_uuid: string`，`status_category: "blocked"\|"waiting"\|"completed"\|"review_ready"\|"failed"`，`status_detail`、`is_noteworthy:boolean`、`title`、`description`、`recent_action`、`needs_action`、`artifact_urls:string[]`

**`subtype: "api_retry"`** `SDKAPIRetryMessageSchema`（`coreSchemas.ts:1572`）：
- `attempt:number`、`max_retries:number`、`retry_delay_ms:number`、`error_status: number\|null`、`error`（assistant error enum）

**`subtype: "local_command_output"`** （`coreSchemas.ts:1590`）：`content:string`

**`subtype: "hook_started"`** （`coreSchemas.ts:1604`）：`hook_id`、`hook_name`、`hook_event`

**`subtype: "hook_progress"`** （`coreSchemas.ts:1616`）：`hook_id`、`hook_name`、`hook_event`、`stdout`、`stderr`、`output`

**`subtype: "hook_response"`** （`coreSchemas.ts:1631`）：`hook_id`、`hook_name`、`hook_event`、`output`、`stdout`、`stderr`、`exit_code?`、`outcome: "success"\|"error"\|"cancelled"`

**`subtype: "task_notification"`** （`coreSchemas.ts:1694`）：`task_id`、`tool_use_id?`、`status: "completed"\|"failed"\|"stopped"`、`output_file`、`summary`、`usage?:{total_tokens,tool_uses,duration_ms}`

**`subtype: "task_started"`** （`coreSchemas.ts:1715`）：`task_id`、`tool_use_id?`、`description`、`task_type?`、`workflow_name?`、`prompt?`

**`subtype: "task_progress"`** （`coreSchemas.ts:1750`）：`task_id`、`tool_use_id?`、`description`、`usage:{total_tokens,tool_uses,duration_ms}`、`last_tool_name?`、`summary?`

**`subtype: "session_state_changed"`** （`coreSchemas.ts:1735`）：`state: "idle"\|"running"\|"requires_action"`

**`subtype: "files_persisted"`** （`coreSchemas.ts:1672`）：`files: Array<{filename,file_id}>`、`failed: Array<{filename,error}>`、`processed_at:string`

**`subtype: "elicitation_complete"`** （`coreSchemas.ts:1779`）：`mcp_server_name`、`elicitation_id`

> `system.subtype` 全部取值汇总：`init`、`compact_boundary`、`status`、`post_turn_summary`、`api_retry`、`local_command_output`、`hook_started`、`hook_progress`、`hook_response`、`task_notification`、`task_started`、`task_progress`、`session_state_changed`、`files_persisted`、`elicitation_complete`。

### B.5 `type: "stream_event"` — 增量流（仅当 `--include-partial-messages`）

`SDKPartialAssistantMessageSchema`（`coreSchemas.ts:1496`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `"stream_event"` | |
| `event` | RawMessageStreamEvent | 占位符 `z.unknown()`，实际是 **Anthropic Messages API 的 SSE 流事件**（`message_start`/`content_block_start`/`content_block_delta`/`content_block_stop`/`message_delta`/`message_stop`），见 §B.10.3 |
| `parent_tool_use_id` | `string \| null` | |
| `uuid` / `session_id` | | |

### B.6 `type: "tool_progress"` — 工具运行进度

`SDKToolProgressMessageSchema`（`coreSchemas.ts:1648`）：
`tool_use_id`、`tool_name`、`parent_tool_use_id: string\|null`、`elapsed_time_seconds:number`、`task_id?`、`uuid`、`session_id`

### B.7 `type: "tool_use_summary"`

`SDKToolUseSummaryMessageSchema`（`coreSchemas.ts:1769`）：
`summary:string`、`preceding_tool_use_ids:string[]`、`uuid`、`session_id`

### B.8 `type: "auth_status"`

`SDKAuthStatusMessageSchema`（`coreSchemas.ts:1661`，需 `--enable-auth-status`）：
`isAuthenticating:boolean`、`output:string[]`、`error?:string`、`uuid`、`session_id`

### B.9 `type: "rate_limit_event"` / `type: "prompt_suggestion"`

- `rate_limit_event`（`coreSchemas.ts:1358`）：`rate_limit_info`（结构见 `SDKRateLimitInfoSchema`，`coreSchemas.ts:1305`：`status: "allowed"\|"allowed_warning"\|"rejected"`、`resetsAt?`、`rateLimitType?`、`utilization?`、`overageStatus?` …）、`uuid`、`session_id`
- `prompt_suggestion`（`coreSchemas.ts:1795`，@internal，需 `promptSuggestions`）：`suggestion:string`、`uuid`、`session_id`

### B.10 `message` 字段内部结构（Anthropic Messages API）

`assistant` / `user` 的 `message` 是 Anthropic Messages API 的标准对象。SDK schema 用占位符 `z.unknown()`，但 wire 上就是标准 Anthropic Message。结构：

#### B.10.1 assistant message
```json
{
  "id": "msg_01...",
  "type": "message",
  "role": "assistant",
  "model": "claude-opus-4-...",
  "content": [ /* content blocks，见 B.10.3 */ ],
  "stop_reason": "end_turn",        // 或 "tool_use" / "max_tokens" / "stop_sequence" / "pause_turn" / null
  "stop_sequence": null,
  "usage": { "input_tokens":..., "output_tokens":..., "cache_creation_input_tokens":..., "cache_read_input_tokens":... }
}
```

#### B.10.2 user message
```json
{ "role": "user", "content": "纯文本" }
```
或
```json
{ "role": "user", "content": [ {"type":"text","text":"..."}, {"type":"tool_result","tool_use_id":"toolu_...","content":[...],"is_error":false} ] }
```

#### B.10.3 content block 形态（最关键，复刻必须支持）

content 是 block 数组，每个 block 有 `type`：

- **text**：`{"type":"text","text":"..."}`
- **thinking**：`{"type":"thinking","thinking":"...","signature":"..."}`（扩展思考。注意：当前 CLI 版本扩展思考多为 redacted/加密，stream 里只发 `signature_delta`，不发明文 `thinking_delta`）
- **redacted_thinking**：`{"type":"redacted_thinking","data":"..."}`
- **tool_use**：`{"type":"tool_use","id":"toolu_01...","name":"Bash","input":{...}}`
- **tool_result**（出现在 user content 里）：`{"type":"tool_result","tool_use_id":"toolu_01...","content": "..." | [blocks], "is_error":false}`
- **image**：`{"type":"image","source":{"type":"base64","media_type":"image/png","data":"..."}}`
- **document** 等

stream_event（B.5）的 `event` 对应这些 block 的增量：
```json
{"type":"stream_event","uuid":"...","session_id":"...","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"片段"}}}
```
delta 种类：`text_delta`、`input_json_delta`（tool_use 的 input 增量）、`thinking_delta`、`signature_delta`。

### B.11 `ModelUsage`（result.modelUsage 的 value）

`coreSchemas.ts:17`：
```ts
{ inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens,
  webSearchRequests, costUSD, contextWindow, maxOutputTokens }  // 全部 number
```

### B.12 streamlined 变体（@internal，一般不发给标准 SDK consumer）

- `streamlined_text`（`coreSchemas.ts:1369`）：`{type:"streamlined_text", text, session_id, uuid}`
- `streamlined_tool_use_summary`（`coreSchemas.ts:1384`）：`{type:"streamlined_tool_use_summary", tool_summary, session_id, uuid}`

---

## C. Control protocol（双向，穿插在同一对 stdio 上）

### C.1 顶层封套

**control_request**（`controlSchemas.ts:578` `SDKControlRequestSchema`）：
```ts
z.object({
  type: z.literal('control_request'),
  request_id: z.string(),
  request: SDKControlRequestInnerSchema(),   // { subtype: '...', ...subtype-specific }
})
```
即：
```json
{ "type":"control_request", "request_id":"<uuid>", "request": { "subtype":"...", /* ... */ } }
```
> **注意 `subtype` 在内层 `request` 里，不在顶层。** 这是和 `result`/`system` 消息（subtype 在顶层）的关键区别。

**control_response**（`controlSchemas.ts:605` `SDKControlResponseSchema`）：顶层 `type:"control_response"`，里面 `response` 是成功或错误两种之一：

成功（`controlSchemas.ts:586` `ControlResponseSchema`）：
```json
{ "type":"control_response", "response": { "subtype":"success", "request_id":"<uuid>", "response": { /* 可选的 payload，Record<string,unknown> */ } } }
```

错误（`controlSchemas.ts:594` `ControlErrorResponseSchema`）：
```json
{ "type":"control_response", "response": { "subtype":"error", "request_id":"<uuid>", "error":"错误消息", "pending_permission_requests": [ /* 可选，SDKControlRequest[] */ ] } }
```

> 成功响应里 payload 嵌套两层 `response`：顶层 `response.response`。错误用 `response.error`（字符串）。`pending_permission_requests` 只在 `initialize` 重复初始化报错时出现（`print.ts:4362`）。

**control_cancel_request**（`controlSchemas.ts:612`）：
```json
{ "type":"control_cancel_request", "request_id":"<uuid>" }
```
用于取消一个进行中的 control_request（如 abort 掉一个 can_use_tool 提示）。

### C.2 request_id 生成与配对

- 生成：`randomUUID()`（`structuredIO.ts:473` `sendRequest` 的默认参数 `requestId: string = randomUUID()`）。
- 配对：发送方维护 `pendingRequests: Map<request_id, {resolve, reject, schema}>`（`structuredIO.ts:137`）。收到 `control_response` 时按 `response.response.request_id` 查表，resolve/reject 对应 promise（`structuredIO.ts:374`）。
- 兼容性 shim：旧 iOS 端会发 `requestId`（驼峰）而非 `request_id`。`normalizeControlMessageKeys`（`utils/controlMessageCompat.ts`）在解析前把顶层和 `response` 里的 `requestId` 改名为 `request_id`。**Java 复刻读入时建议同样容错 `requestId`；写出统一用 `request_id`。**

### C.3 各 control subtype 精确字段

下面按 subtype 列。请求 schema 在 `controlSchemas.ts`，响应处理在 `cli/print.ts`。

---

#### `initialize`（握手，SDK → CLI）

**请求**（`controlSchemas.ts:57`）：
```ts
{
  subtype: 'initialize',
  hooks?: Record<HookEvent, SDKHookCallbackMatcher[]>,   // 见下
  sdkMcpServers?: string[],                              // in-process MCP server 名字列表
  jsonSchema?: Record<string, unknown>,                 // 结构化输出 schema
  systemPrompt?: string,                                 // 走 stdin 避免 ARG_MAX
  appendSystemPrompt?: string,
  agents?: Record<string, AgentDefinition>,              // 见 §C.4
  promptSuggestions?: boolean,
  agentProgressSummaries?: boolean,
}
```
`SDKHookCallbackMatcher`（`controlSchemas.ts:43`）：`{ matcher?: string, hookCallbackIds: string[], timeout?: number }`。`HookEvent` 是 28 个枚举（见 §C.5）。

**响应**（`controlSchemas.ts:77` `SDKControlInitializeResponseSchema`，CLI 构造见 `print.ts:4453`），放在成功响应的 `response.response`：
```ts
{
  commands: SlashCommand[],            // {name, description, argumentHint}
  agents: AgentInfo[],                 // {name, description, model?}
  output_style: string,
  available_output_styles: string[],
  models: ModelInfo[],                 // 见 §C.6
  account: AccountInfo,                // {email?, organization?, subscriptionType?, tokenSource?, apiKeySource?, apiProvider?}
  pid?: number,                        // @internal CLI 进程 PID（tmux socket 隔离用）
  fast_mode_state?: 'off'|'cooldown'|'on',
}
```
`AccountInfo.apiProvider` ∈ `firstParty`/`bedrock`/`vertex`/`foundry`。

重复初始化 → 错误响应 `error:"Already initialized"` + `pending_permission_requests`（`print.ts:4355`）。

示例（SDK 发）：
```json
{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize","hooks":{"PreToolUse":[{"hookCallbackIds":["cb_1"],"matcher":"Bash"}]},"sdkMcpServers":["myserver"]}}
```
示例（CLI 回）：
```json
{"type":"control_response","response":{"subtype":"success","request_id":"init-1","response":{"commands":[],"agents":[],"output_style":"default","available_output_styles":["default"],"models":[{"value":"claude-opus-4-...","displayName":"Opus","description":"..."}],"account":{"email":"a@b.com","apiProvider":"firstParty"},"pid":12345}}}
```

---

#### `can_use_tool`（权限请求，CLI → SDK）

CLI 在工具执行前需要 SDK consumer（host）决定是否放行。**这是 CLI 主动发往 SDK 的 control_request。**

**请求**（`controlSchemas.ts:106`，CLI 构造见 `structuredIO.ts:590`）：
```ts
{
  subtype: 'can_use_tool',
  tool_name: string,
  input: Record<string, unknown>,            // 工具入参
  permission_suggestions?: PermissionUpdate[],// 见 §C.7
  blocked_path?: string,
  decision_reason?: string,
  title?: string,
  display_name?: string,
  tool_use_id: string,                        // 必填
  agent_id?: string,                          // 来自 subagent 时
  description?: string,
}
```

**响应**（SDK → CLI；CLI 用 `PermissionPromptToolResultSchema` 校验，见 `utils/permissions/PermissionPromptToolResultSchema.ts:75`）。放在成功响应的 `response.response`，是 allow / deny 二选一：

allow（`PermissionPromptToolResultSchema.ts:44`）：
```ts
{
  behavior: 'allow',
  updatedInput: Record<string, unknown>,       // 注意：CLI 接收端这里【必填】（不是 optional）
  updatedPermissions?: PermissionUpdate[],      // "始终允许"等持久化规则；坏数据被忽略而非整体拒绝
  toolUseID?: string,
  decisionClassification?: 'user_temporary'|'user_permanent'|'user_reject',
}
```
deny（`PermissionPromptToolResultSchema.ts:65`）：
```ts
{
  behavior: 'deny',
  message: string,                              // 必填
  interrupt?: boolean,                          // true 时 CLI 会 abort 整个回合
  toolUseID?: string,
  decisionClassification?: 'user_temporary'|'user_permanent'|'user_reject',
}
```

> **重要细节 1（updatedInput 必填）**：CLI 接收端的 `PermissionAllowResultSchema.updatedInput` 是 `z.record(...)` **没有 `.optional()`**。SDK 类型层的 `PermissionResultSchema`（`coreSchemas.ts:315`）把它标为 optional，但实际发给 CLI 时**应当带上**。手机端从推送通知回应时拿不到原始 input，会发 `updatedInput: {}`，CLI 把空对象当作"用原始 input"（`PermissionPromptToolResultSchema.ts:110`）。**Java 复刻 allow 时务必带 `updatedInput`，没有修改就发 `{}`。**
>
> **重要细节 2（两个 ID 字段名不同）**：请求里是 `tool_use_id`（snake_case），响应里是 `toolUseID`（camelCase）。不要写错。
>
> **重要细节 3（deny+interrupt）**：deny 且 `interrupt:true` 时 CLI 调 `toolUseContext.abortController.abort()` 中断当前回合（`PermissionPromptToolResultSchema.ts:117`）。

allow 响应示例：
```json
{"type":"control_response","response":{"subtype":"success","request_id":"perm-7","response":{"behavior":"allow","updatedInput":{},"toolUseID":"toolu_01abc"}}}
```
deny 响应示例：
```json
{"type":"control_response","response":{"subtype":"success","request_id":"perm-7","response":{"behavior":"deny","message":"用户拒绝","interrupt":false}}}
```
> 注意：deny 也是放在 **success** 响应里（`subtype:"success"`），不是 error 响应。error 响应表示"协议层失败"，allow/deny 是"业务决策"。

---

#### `interrupt`（SDK → CLI）

请求（`controlSchemas.ts:97`）：`{ subtype: 'interrupt' }`
处理（`print.ts:2831`）：abort 当前 `abortController`，清空 suggestion 状态，回 `sendControlResponseSuccess(message)`（**空 payload**）。
响应：`{type:"control_response",response:{subtype:"success",request_id:"<id>"}}`

---

#### `set_permission_mode`（SDK → CLI）

请求（`controlSchemas.ts:124`）：
```ts
{ subtype: 'set_permission_mode', mode: PermissionMode, ultraplan?: boolean }
```
`PermissionMode`（`coreSchemas.ts:337`）∈ `default` / `acceptEdits` / `bypassPermissions` / `plan` / `dontAsk`。
处理（`print.ts:2918` → `handleSetPermissionMode`，`print.ts:4568`）：
- 切到 `bypassPermissions` 但被 settings 禁用 → error `"Cannot set permission mode to bypassPermissions because it is disabled..."`
- 切到 `bypassPermissions` 但启动时没加 `--dangerously-skip-permissions` → error `"...the session was not launched with --dangerously-skip-permissions"`
- 否则成功，**空 payload**。

---

#### `set_model`（SDK → CLI）

请求（`controlSchemas.ts:137`）：`{ subtype: 'set_model', model?: string }`（不传或 `"default"` 用默认主模型）。
处理（`print.ts:2933`）：切换主循环模型，注入面包屑，回成功（**空 payload**）。

---

#### `set_max_thinking_tokens`（SDK → CLI）

请求（`controlSchemas.ts:146`）：`{ subtype: 'set_max_thinking_tokens', max_thinking_tokens: number | null }`
处理（`print.ts:2945`）：`null` → 清空 thinkingConfig；`0` → `{type:'disabled'}`；正数 → `{type:'enabled', budgetTokens:n}`。回成功（空 payload）。

---

#### `mcp_status` / `mcp_message` / `mcp_set_servers` / `mcp_reconnect` / `mcp_toggle`（MCP 相关，SDK → CLI）

- **`mcp_status`**（`controlSchemas.ts:157`）：`{ subtype:'mcp_status' }`。响应（`controlSchemas.ts:165`）：`{ mcpServers: McpServerStatus[] }`。`McpServerStatus` 结构见 `coreSchemas.ts:167`（`name`、`status: connected/failed/needs-auth/pending/disabled`、`serverInfo?`、`error?`、`config?`、`scope?`、`tools?`、`capabilities?`）。处理 `print.ts:2957`。

- **`mcp_message`**（in-process MCP 转发，`controlSchemas.ts:374`）：`{ subtype:'mcp_message', server_name: string, message: JSONRPCMessage }`。`message` 是 **MCP 的 JSON-RPC 报文原样塞进来**（占位符 `z.unknown()`，`controlSchemas.ts:37`，实际是 `@modelcontextprotocol/sdk` 的 `JSONRPCMessage`，即标准 `{jsonrpc:"2.0", id, method, params}` / `{jsonrpc:"2.0", id, result}` / notification）。
  - **方向 SDK → CLI**：处理 `print.ts:2979`，把 message 喂给对应 SDK 客户端的 transport，回成功（空 payload）。
  - **方向 CLI → SDK**（CLI 调 SDK in-process MCP server）：`structuredIO.ts:758` `sendMcpMessage`，发 `{subtype:'mcp_message', server_name, message}`，期望响应 `response.response = { mcp_response: JSONRPCMessage }`（`structuredIO.ts:762`）。

- **`mcp_set_servers`**（`controlSchemas.ts:384`）：`{ subtype:'mcp_set_servers', servers: Record<string, McpServerConfig> }`。响应（`controlSchemas.ts:393`）：`{ added: string[], removed: string[], errors: Record<string,string> }`。处理 `print.ts:3055`。`McpServerConfig`（`coreSchemas.ts:142` union）：stdio `{type?:'stdio',command,args?,env?}` / sse `{type:'sse',url,headers?}` / http `{type:'http',url,headers?}` / sdk `{type:'sdk',name}`。

- **`mcp_reconnect`**（`controlSchemas.ts:435`）：`{ subtype:'mcp_reconnect', serverName: string }`。成功空 payload / error。处理 `print.ts:3133`。

- **`mcp_toggle`**（`controlSchemas.ts:444`）：`{ subtype:'mcp_toggle', serverName: string, enabled: boolean }`。处理 `print.ts:3206`。

---

#### `hook_callback`（CLI → SDK）

CLI 触发了一个由 SDK 注册的 hook（initialize 时通过 `hookCallbackIds` 注册）。**CLI 主动发往 SDK。**

请求（`controlSchemas.ts:363`，CLI 构造见 `structuredIO.ts:671`）：
```ts
{ subtype: 'hook_callback', callback_id: string, input: HookInput, tool_use_id?: string }
```
`HookInput` 是 28 种 hook 输入之一（§C.5，union 在 `coreSchemas.ts:767`），每个都带 `session_id`、`transcript_path`、`cwd`、`hook_event_name` 等公共字段。

响应（SDK → CLI）：放 `response.response`，是 `HookJSONOutput`（`coreSchemas.ts:972`）：
- 异步：`{ async: true, asyncTimeout?: number }`
- 同步：`{ continue?, suppressOutput?, stopReason?, decision?: 'approve'|'block', systemMessage?, reason?, hookSpecificOutput?: {...} }`

`hookSpecificOutput` 按 hookEventName 不同（如 `PreToolUse` 可返回 `permissionDecision`/`updatedInput`/`additionalContext`，见 `coreSchemas.ts:806`）。SDK 端 callback 出错时回 `{}`（`structuredIO.ts:684`）。

---

#### `get_context_usage`（SDK → CLI）

请求（`controlSchemas.ts:175`）：`{ subtype: 'get_context_usage' }`。
响应（`controlSchemas.ts:205`，超长）：上下文窗口用量明细。关键字段：`categories[]`、`totalTokens`、`maxTokens`、`rawMaxTokens`、`percentage`、`gridRows[][]`、`model`、`memoryFiles[]`、`mcpTools[]`、`agents[]`、`isAutoCompactEnabled`、`apiUsage: {input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens} | null` 等。处理 `print.ts:2961`。

---

#### `rewind_files`（SDK → CLI）

请求（`controlSchemas.ts:308`）：`{ subtype:'rewind_files', user_message_id: string, dry_run?: boolean }`。
响应（`controlSchemas.ts:318`）：`{ canRewind: boolean, error?, filesChanged?: string[], insertions?: number, deletions?: number }`。处理 `print.ts:2995`。

---

#### `cancel_async_message`（SDK → CLI）

请求（`controlSchemas.ts:330`）：`{ subtype:'cancel_async_message', message_uuid: string }`。
响应（`controlSchemas.ts:341`）：`{ cancelled: boolean }`。处理 `print.ts:3011`（按 uuid 从命令队列移除）。

---

#### `seed_read_state`（SDK → CLI）

请求（`controlSchemas.ts:351`）：`{ subtype:'seed_read_state', path: string, mtime: number }`。
响应：成功空 payload（`print.ts:3017`）。用途：把一个 path+mtime 灌进 readFileState 缓存，避免 Edit 校验失败。

---

#### `reload_plugins`（SDK → CLI）

请求（`controlSchemas.ts:405`）：`{ subtype:'reload_plugins' }`。
响应（`controlSchemas.ts:415`）：`{ commands: SlashCommand[], agents: AgentInfo[], plugins: Array<{name,path,source?}>, mcpServers: McpServerStatus[], error_count: number }`。处理 `print.ts:3065`。

---

#### `stop_task`（SDK → CLI）

请求（`controlSchemas.ts:455`）：`{ subtype:'stop_task', task_id: string }`。
响应：成功 `{}` / error（`print.ts:3772`）。

---

#### `apply_flag_settings`（SDK → CLI）

请求（`controlSchemas.ts:464`）：`{ subtype:'apply_flag_settings', settings: Record<string, unknown> }`。
处理（`print.ts:3699`）：浅合并进 flag settings 层；`null` 值表示删除该 key；含 `model` 时切模型。回成功（空 payload）。

---

#### `get_settings`（SDK → CLI）

请求（`controlSchemas.ts:475`）：`{ subtype:'get_settings' }`。
响应（`controlSchemas.ts:485`）：
```ts
{
  effective: Record<string, unknown>,
  sources: Array<{ source: 'userSettings'|'projectSettings'|'localSettings'|'flagSettings'|'policySettings', settings: Record<string,unknown> }>,
  applied?: { model: string, effort: 'low'|'medium'|'high'|'max' | null },
}
```
处理 `print.ts:3756`。

---

#### `elicitation`（CLI → SDK）

MCP server 请求用户输入；CLI 转发给 SDK consumer 处理。**CLI 主动发往 SDK。**

请求（`controlSchemas.ts:522`，CLI 构造 `structuredIO.ts:704`）：
```ts
{ subtype:'elicitation', mcp_server_name: string, message: string, mode?: 'form'|'url', url?: string, elicitation_id?: string, requested_schema?: Record<string,unknown> }
```
响应（`controlSchemas.ts:538`，SDK → CLI）：`{ action: 'accept'|'decline'|'cancel', content?: Record<string,unknown> }`。SDK 出错/超时默认回 `{action:'cancel'}`（`structuredIO.ts:718`）。

---

#### keep_alive / update_environment_variables（非 control_request 但同流）

- `keep_alive`（`controlSchemas.ts:621`）：`{ type:"keep_alive" }`。双向都可发，收到静默忽略（`structuredIO.ts:344`）。WebSocket transport 周期性发以重置代理空闲计时器（`WebSocketTransport.ts:20` 帧常量 `'{"type":"keep_alive"}\n'`）。stdio 模式一般用不到，但收到要能忽略。
- `update_environment_variables`（`controlSchemas.ts:629`，SDK → CLI）：`{ type:"update_environment_variables", variables: Record<string,string> }`。CLI 直接 set 进 `process.env`（`structuredIO.ts:348`）。用于刷新 auth token 等。

### C.4 `AgentDefinition`（initialize.agents 的 value）

`coreSchemas.ts:1110`：
```ts
{
  description: string,                 // 必填
  tools?: string[], disallowedTools?: string[],
  prompt: string,                      // 必填，agent system prompt
  model?: string,                      // alias 或全 ID 或 'inherit'
  mcpServers?: AgentMcpServerSpec[],
  criticalSystemReminder_EXPERIMENTAL?: string,
  skills?: string[], initialPrompt?: string,
  maxTurns?: number, background?: boolean,
  memory?: 'user'|'project'|'local',
  effort?: ('low'|'medium'|'high'|'max') | number,
  permissionMode?: PermissionMode,
}
```

### C.5 Hook 事件枚举（28 个）

`coreSchemas.ts:355` `HOOK_EVENTS`：
`PreToolUse` / `PostToolUse` / `PostToolUseFailure` / `Notification` / `UserPromptSubmit` / `SessionStart` / `SessionEnd` / `Stop` / `StopFailure` / `SubagentStart` / `SubagentStop` / `PreCompact` / `PostCompact` / `PermissionRequest` / `PermissionDenied` / `Setup` / `TeammateIdle` / `TaskCreated` / `TaskCompleted` / `Elicitation` / `ElicitationResult` / `ConfigChange` / `WorktreeCreate` / `WorktreeRemove` / `InstructionsLoaded` / `CwdChanged` / `FileChanged`

每个 HookInput 公共字段（`coreSchemas.ts:387` BaseHookInput）：`session_id`、`transcript_path`、`cwd`、`permission_mode?`、`agent_id?`、`agent_type?`，再 `.and()` 上各自的 `hook_event_name` literal + 专属字段（如 PreToolUse 加 `tool_name`/`tool_input`/`tool_use_id`）。

### C.6 `ModelInfo`（initialize 响应里 models[]）

`coreSchemas.ts:1047`：`{ value, displayName, description, supportsEffort?, supportedEffortLevels?, supportsAdaptiveThinking?, supportsFastMode?, supportsAutoMode? }`。

### C.7 `PermissionUpdate`（discriminated union by `type`）

`coreSchemas.ts:263`：
- `{ type:'addRules', rules: PermissionRuleValue[], behavior: 'allow'|'deny'|'ask', destination }`
- `{ type:'replaceRules', rules, behavior, destination }`
- `{ type:'removeRules', rules, behavior, destination }`
- `{ type:'setMode', mode: PermissionMode, destination }`
- `{ type:'addDirectories', directories: string[], destination }`
- `{ type:'removeDirectories', directories: string[], destination }`

`PermissionRuleValue`：`{ toolName: string, ruleContent?: string }`。
`destination` ∈ `userSettings`/`projectSettings`/`localSettings`/`session`/`cliArg`（`coreSchemas.ts:242`）。

### C.8 CLI 端独有、非 SDK 公开协议的 subtype（复刻可忽略）

`print.ts` 还处理了 schema union 里**没有**的 subtype，这些来自 IDE / CCR / claude.ai bridge，不是标准 SDK 协议，复刻 SDK 时**不需要实现**，但解析时不要因为未知 subtype 崩溃：
`end_session`（`print.ts:2850`）、`channel_enable`、`mcp_authenticate`、`mcp_oauth_callback_url`、`claude_authenticate`、`claude_oauth_callback`、`claude_oauth_wait_for_completion`、`mcp_clear_auth`、`generate_session_title`、`side_question`、`remote_control`。

---

## D. Options → CLI / initialize 映射

> SDK 的 `Options` 类型实现不在本源码树（在 npm 包的 `runtimeTypes.ts`）。下表是**反推**：把已知的 SDK option 概念映射到本源码确认的 flag 或 control 字段。已确认的 flag 名打 ✅，反推/未在本源码确认 flag 名的打 ⚠️。

| SDK Option（概念） | 映射方式 | flag / 字段 | 来源 |
|---|---|---|---|
| prompt（string 或 AsyncIterable） | stdin NDJSON `user` 消息 / 位置参数 | — | `query()` 签名 `agentSdkTypes.ts:112` |
| permissionMode | CLI flag ✅ | `--permission-mode` | `main.tsx:976` |
| canUseTool 回调 | **不是 flag**，靠 stream-json 双向 + 回应 `can_use_tool` control_request | — | `structuredIO.ts:533` |
| hooks | initialize control 的 `hooks` 字段 + `hook_callback` 回调 | initialize.hooks | `controlSchemas.ts:61` |
| mcpServers（外部） | CLI flag ✅ | `--mcp-config` | `main.tsx` |
| mcpServers（in-process / sdk 类型） | initialize control 的 `sdkMcpServers` + `mcp_message` 转发 | initialize.sdkMcpServers | `controlSchemas.ts:64`、`print.ts:2870` |
| systemPrompt | flag ✅ 或 initialize control | `--system-prompt` / initialize.systemPrompt | `main.tsx`、`controlSchemas.ts:66` |
| appendSystemPrompt | flag ✅ 或 initialize control | `--append-system-prompt` / initialize.appendSystemPrompt | 同上 |
| agents | flag ✅ 或 initialize control（避 ARG_MAX） | `--agents` / initialize.agents | `main.tsx`、`controlSchemas.ts:68` |
| allowedTools | CLI flag ✅ | `--allowedTools` / `--allowed-tools` | `main.tsx` |
| disallowedTools | CLI flag ✅ | `--disallowedTools` / `--disallowed-tools` | `main.tsx` |
| model | CLI flag ✅ 或 `set_model` control | `--model`（alias 或全名，`default`=默认）/ set_model | `main.tsx:993` |
| effort | CLI flag ✅ | `--effort low\|medium\|high\|max` | `main.tsx:993` |
| fallbackModel | CLI flag ✅ | `--fallback-model` | `main.tsx:1000` |
| maxTurns | CLI flag ✅（仅 print） | `--max-turns` | `main.tsx` |
| maxBudgetUsd | CLI flag ✅（仅 print） | `--max-budget-usd` | `main.tsx` |
| maxThinkingTokens | flag ✅（已废弃）或 `set_max_thinking_tokens` control | `--max-thinking-tokens` / set_max_thinking_tokens | `main.tsx`、`controlSchemas.ts:146` |
| thinking | CLI flag ✅ | `--thinking enabled\|adaptive\|disabled` | `main.tsx` |
| includePartialMessages | CLI flag ✅ | `--include-partial-messages` | `main.tsx`、校验 `main.tsx:1848` |
| includeHookEvents（输出全 hook 事件） | CLI flag ✅ | `--include-hook-events` | `main.tsx:1230` |
| replayUserMessages | CLI flag ✅ | `--replay-user-messages` | `main.tsx:1840` |
| permissionPromptToolName | CLI flag ✅（仅 print） | `--permission-prompt-tool` | `main.tsx` |
| resume / continue / forkSession | CLI flag ✅ | `--resume` / `--continue` / `--fork-session` | `main.tsx` |
| sessionId | CLI flag ✅ | `--session-id` | `main.tsx` |
| persistSession=false | CLI flag ✅（仅 print） | `--no-session-persistence` | `main.tsx` |
| additionalDirectories | CLI flag ✅ | `--add-dir` | `main.tsx` |
| settings | CLI flag ✅ | `--settings` | `main.tsx` |
| settingSources | CLI flag ✅ | `--setting-sources` | `main.tsx` |
| strictMcpConfig | CLI flag ✅ | `--strict-mcp-config` | `main.tsx` |
| dangerouslySkipPermissions | CLI flag ✅ | `--dangerously-skip-permissions` | `main.tsx` |
| jsonSchema（结构化输出） | flag ✅ 或 initialize control | `--json-schema` / initialize.jsonSchema | `main.tsx`、`controlSchemas.ts:65` |
| promptSuggestions | initialize control | initialize.promptSuggestions | `controlSchemas.ts:69` |
| agentProgressSummaries | initialize control | initialize.agentProgressSummaries | `controlSchemas.ts:70` |
| betas | CLI flag ✅ | `--betas` | `main.tsx:1000` |
| agent（主线程 agent） | CLI flag ✅ | `--agent` | `main.tsx` |
| 运行时切模型 / 模式 / 思考预算 | control_request | set_model / set_permission_mode / set_max_thinking_tokens | `controlSchemas.ts` |
| 运行时刷新环境变量 | stdin 消息 | update_environment_variables | `controlSchemas.ts:629` |

**Query 接口方法 → control subtype 对应**（反推自 structuredIO 的调用点 + 标准 SDK API）：
- `query.interrupt()` → `interrupt`
- `query.setPermissionMode(mode)` → `set_permission_mode`
- `query.setModel(model)` → `set_model`
- `query.setMaxThinkingTokens(n)` → `set_max_thinking_tokens`
- `query.mcpServerStatus()` → `mcp_status`
- `query.getContextUsage()` → `get_context_usage`
- `query.rewindFiles(...)` → `rewind_files`
- `query.setMcpServers(...)` → `mcp_set_servers`
- `query.reloadPlugins()` → `reload_plugins`
- `query.getSettings()` → `get_settings`
- `query.stopTask(id)` → `stop_task`

---

## E. 关键约束 / 坑（源码可证）

### E.1 stream-json **强制** `--verbose`（硬约束）
`cli/print.ts:787`：
```ts
if (options.outputFormat === 'stream-json' && !options.verbose) {
  // 'Error: When using --print, --output-format=stream-json requires --verbose\n'
  process.exit(1)
}
```
**Java spawn `claude` 时若用 `--print --output-format stream-json`，必须同时加 `--verbose`，否则 CLI 立即退出。**

### E.2 input-format=stream-json **强制** output-format=stream-json
`main.tsx:1823`：
```ts
if (inputFormat === 'stream-json' && outputFormat !== 'stream-json') {
  // 'Error: --input-format=stream-json requires output-format=stream-json.'
  process.exit(1)
}
```

### E.3 `--include-partial-messages` 要求 print + stream-json
`main.tsx:1848`：否则 `process.exit(1)`。不开这个 flag 就**不会**收到 `stream_event`。

### E.4 `--replay-user-messages` 要求双向 stream-json
`main.tsx:1840`：否则退出。开启后 user 消息回显带 `isReplay:true`。

### E.5 启用 canUseTool 时的 permission-mode
canUseTool 走的是 `can_use_tool` control_request 通道（stream-json 双向）。CLI 端是否真的发 `can_use_tool` 取决于权限模式：只有当规则判定为 `ask`（既非 allow 也非 deny，`structuredIO.ts:554`）才会发起 SDK 提示。`bypassPermissions` / 已 allow 的工具不会触发。**实践中要让 canUseTool 生效，应使用 `--permission-mode default`（或 dontAsk 看具体需要）**，并保证 stdin 双向通道开着。（本源码未见"启用 canUseTool 自动强制某 mode"的代码——是否强制由 SDK npm 包侧决定，见 §不确定。）

### E.6 NDJSON 行安全：转义 U+2028 / U+2029
`cli/ndjsonSafeStringify.ts`：写出前把字符串里的 ` `(LINE SEPARATOR) / ` `(PARAGRAPH SEPARATOR) 转义成 `\\u2028` / `\\u2029`，否则按 JS 行终止符语义切流的接收方会把 JSON 拦腰截断导致丢消息。**Java 复刻写出方向建议同样转义这两个码点**（标准 Jackson/Gson 默认不转义它们，需自定义）。

### E.7 control_response 顶层 type、内层 subtype（封套不对称）
- `control_request`：顶层 `{type, request_id, request:{subtype,...}}` — subtype 在内层。
- `control_response`：顶层 `{type, response:{subtype:'success'|'error', request_id, ...}}` — subtype 在 `response` 里，且 `request_id` 在 `response` 里（不在顶层）。
- 成功 payload 在 `response.response`（双层嵌套）。

### E.8 requestId 驼峰兼容
读入时若顶层或 `response` 里出现 `requestId`（驼峰）应改名为 `request_id`（`utils/controlMessageCompat.ts`）。写出统一 snake_case。

### E.9 can_use_tool 的 ID 字段名不一致
请求 `tool_use_id`（snake），响应 `toolUseID`（camel）。

### E.10 deny+interrupt 会中断回合
deny 响应带 `interrupt:true` 时 CLI 直接 abort 当前回合（`PermissionPromptToolResultSchema.ts:117`）。

### E.11 未知顶层消息类型/未知 control subtype 的处理
- CLI 端对未知顶层 `type` 仅 log warn 并忽略（`structuredIO.ts:437`）。
- 解析 JSON 失败时 CLI 端 `console.error` 后 `process.exit(1)`（`structuredIO.ts:457`）——**坏行会杀死进程**。Java 复刻写出方向务必保证每行是合法单行 JSON。
- 空行被忽略。

### E.12 control_response 重复投递的去重
CLI 端用 `resolvedToolUseIds` Set（上限 1000）记录已解析的 tool_use_id，丢弃重复的 control_response（`structuredIO.ts:133/176/385`），避免重复 assistant 消息触发 API 400 "tool_use ids must be unique"。复刻 SDK 端同理：对同一 request_id / toolUseID 的重复响应应幂等处理。

### E.13 输入流关闭时拒绝所有挂起请求
stdin 关闭（EOF）后，所有 `pendingRequests` 被 reject：`"Tool permission stream closed before response received"`（`structuredIO.ts:255`）。

---

## F. 端到端时序示例（stdio 模式）

```
SDK spawn:  claude -p --input-format stream-json --output-format stream-json --verbose --permission-mode default

CLI→SDK   {"type":"system","subtype":"init","session_id":"s1","uuid":"u0","claude_code_version":"2.1.159","cwd":"/x","tools":["Bash","Read",...],"model":"claude-opus-4-...","permissionMode":"default","mcp_servers":[],"slash_commands":[],"output_style":"default","skills":[],"plugins":[],"apiKeySource":"oauth"}
SDK→CLI   {"type":"control_request","request_id":"init-1","request":{"subtype":"initialize","sdkMcpServers":[],"hooks":{}}}
CLI→SDK   {"type":"control_response","response":{"subtype":"success","request_id":"init-1","response":{"commands":[],"agents":[],"models":[...],"account":{...},"output_style":"default","available_output_styles":["default"],"pid":4242}}}
SDK→CLI   {"type":"user","session_id":"","parent_tool_use_id":null,"message":{"role":"user","content":"列出当前目录"}}
CLI→SDK   {"type":"stream_event","uuid":"u1","session_id":"s1","parent_tool_use_id":null,"event":{"type":"message_start","message":{...}}}      ← 仅当 --include-partial-messages
CLI→SDK   {"type":"assistant","uuid":"u2","session_id":"s1","parent_tool_use_id":null,"message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}],"stop_reason":"tool_use","usage":{...}}}
CLI→SDK   {"type":"control_request","request_id":"perm-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"tool_use_id":"toolu_1"}}      ← 当规则判定为 ask
SDK→CLI   {"type":"control_response","response":{"subtype":"success","request_id":"perm-1","response":{"behavior":"allow","updatedInput":{}}}}
CLI→SDK   {"type":"user","uuid":"u3","session_id":"s1","parent_tool_use_id":null,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file1\nfile2"}]}}
CLI→SDK   {"type":"assistant","uuid":"u4","session_id":"s1","parent_tool_use_id":null,"message":{"role":"assistant","content":[{"type":"text","text":"当前目录有 file1、file2。"}],"stop_reason":"end_turn","usage":{...}}}
CLI→SDK   {"type":"result","subtype":"success","duration_ms":2000,"duration_api_ms":1800,"is_error":false,"num_turns":1,"result":"当前目录有 file1、file2。","stop_reason":"end_turn","total_cost_usd":0.003,"usage":{...},"modelUsage":{...},"permission_denials":[],"uuid":"u5","session_id":"s1"}
```

---

## G. 不确定 / 没找到（未能在本源码确认的点，未编造）

1. **SDK 如何拼命令行参数的精确代码**：SDK npm 包本体（`query()` 实现、`ProcessTransport`、`Options`→argv 的构造函数）**不在**这棵源码树里。本源码是被 spawn 的 CLI 端，只有 `entrypoints/agentSdkTypes.ts` 的类型占位（函数体全是 `throw 'not implemented'`）。§A、§D 里 flag 与 Options 的对应是**反推**（基于 CLI 端的 commander Option 定义与校验逻辑），flag 名本身可信，但"SDK 默认一定加/不加哪些 flag"未在本树确认。

2. ~~`--model` 的确切 flag 名~~ **已确认**：`main.tsx:993` 有 `--model <model>`（alias 或全名，`default`=默认主模型）和 `--effort <level>`。此条已解决，不再是不确定项。

3. **启用 canUseTool 是否强制某 permission-mode**：本源码（CLI 端）未见"开了 canUseTool 就强制 mode"的逻辑——CLI 只是按权限规则在 `ask` 时发 `can_use_tool`。是否在 SDK npm 包侧做强制（如强制 `default`），需看 SDK 包源码，本树无法确认。

4. **`runtimeTypes.ts` / `controlTypes.ts` / `coreTypes.generated.ts` 的具体内容**：被 import 但文件不在本树（`entrypoints/sdk/` 只有 controlSchemas / coreSchemas / coreTypes）。`Options`、`Query` 接口的完整方法签名、`SDKControlRequest`/`SDKControlResponse` 的 TS 类型定义未能直接读到——但它们的运行时 wire 形态等价于 `controlSchemas.ts` 的 zod schema（已完整还原）。

5. **`message`（assistant/user）与 `event`（stream_event）的精确字段**：schema 里是 `z.unknown()` 占位符（明确注释为 Anthropic SDK 的 `APIAssistantMessage` / `APIUserMessage` / `RawMessageStreamEvent`）。§B.10 给出的是 Anthropic Messages API 的标准结构（来自对该 API 的通识），**不是从本源码逐字段抽取**——content block 的具体可选字段以 Anthropic Messages API 官方为准。

6. **`usage` / `NonNullableUsage` 的精确字段**：`result.usage` 用占位符 `z.unknown()`（`NonNullableUsagePlaceholder`），是 Anthropic Usage 的非空映射。`modelUsage` 的 value（`ModelUsage`）字段已确认（§B.11）。顶层 `usage` 字段列表未在本源码逐字段定义。

7. **`stop_reason` 取值**：result 里是 `z.string().nullable()`（不是 enum），具体字符串值（`end_turn`/`tool_use`/`max_tokens`/`stop_sequence`/`pause_turn`）来自 Anthropic API 通识，本源码未约束枚举。
