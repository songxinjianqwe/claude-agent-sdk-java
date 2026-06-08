# Generative UI 是怎么回事（用具体例子讲透）

> [`ag-ui-copilotkit-接入详解.md`](./ag-ui-copilotkit-接入详解.md) 的专题深入篇。
> 把「generative UI / 前端工具 / HITL」这一串容易绕晕的概念，用三个具体例子 + 时序图讲透。
> 核心问题：「能不能让 Claude 调工具时前端冒出我自定义的组件？」「执行 tool 和渲染组件之间好像还有
> 一层东西？」「后端执行的工具，前端能不能跟着画？」

## 一句话本质

**把 agent 的「工具调用（tool call）」当成「渲染指令」——前端按工具名 + 参数渲染一个真正的
React 组件，而不是只显示一段文字。**

agui-server 的 demo 里其实已经有它的最初级形态：那个 `▸ Bash Done` 工具卡，就是前端收到
`TOOL_CALL_START(Bash)` 事件、用 CopilotKit 默认渲染器画出来的。generative UI 就是把这张默认卡片
**换成你自己写的组件**。

> ⚠️ 一个反直觉但关键的点：**Claude 调用工具时，完全不知道、也不关心前端会渲染成什么样。**
> 它只当自己在调一个「会返回值的普通函数」。把「工具调用」翻译成「组件渲染」的，是 CopilotKit +
> AG-UI 那一层（详见「那一层到底是什么」）。

## 一、最关键的解耦：谁执行工具 ≠ 谁渲染工具

这是理解 generative UI 的钥匙。**「执行一个工具」和「渲染一个工具」是两件独立的事**：

| | 谁执行（handler 在哪） | 前端的角色 | 难度 |
|---|---|---|---|
| **后端工具** | 后端（读写数据库 / 跑命令…） | **旁观者 + 美化者**：订阅 `TOOL_CALL_*` 事件，把过程画好看 | ⭐ 简单（不用回传） |
| **前端工具** | 前端（渲染组件 + 等用户交互 + 回传） | **执行者**：渲染本身就是「执行」 | ⭐⭐⭐ 难（要挂起回传） |

两种工具**都通过 in-process MCP 注册给 Claude**（见第六节），区别只在 handler 里干什么：后端工具真
干活、前端工具把活转发给前端。

## 二、三个例子（从简单到复杂）

### 例子 A — 纯展示：执行 = 渲染（最简单）

你问 Claude：「**北京今天天气怎么样？**」

1. 前端注册一个工具：
   ```js
   { name: "show_weather",
     parameters: { city, temp, condition },        // 它"长什么样"
     render: (args) => <WeatherCard {...args}/> }   // 它"怎么画"
   ```
2. Claude 查到天气，决定「不直接说文字，调那个能画卡片的工具」：
   `show_weather(city="北京", temp=28, condition="晴")`
3. 前端收到这个工具调用 → 把参数当 props 画出 `<WeatherCard city="北京" temp={28}/>`。
4. 工具立即返回「已显示」，Claude 说「已经帮你显示了」。

**在这个例子里「执行 tool」=「渲染组件」，没有第二步。** 工具的「实现」不是算什么值，就是画个卡片。

### 例子 C — 后端工具 + 前端渲染：旁观者 + 美化者（最常见，也最实用）

> 这是最贴近真实业务的形态：**后端声明并执行工具（读写数据库），前端只负责把这个过程画好看。**

假设后端注册了笔记相关的 in-process MCP 工具，handler 真的读写数据库：
```java
ClaudeAgent.tool("open_note", "打开一篇笔记", schema,
    args -> { var note = noteMapper.selectById(args.get("noteId")); // 真查库
              return McpToolResult.text(note.getContent()); });
```

你问：「**帮我看看《项目计划》那篇笔记写了啥**」

```
你        Claude            后端 handler(执行)        前端(只渲染)
 │ 问      │                     │                       │
 │────────►│ 调 open_note(id=42) │                       │
 │         │────────────────────►│ 真去数据库查这条      │
 │         │                     │  ←同时→ AG-UI 发      │
 │         │                     │  TOOL_CALL_START      │
 │         │                     │  (open_note,{id:42})  │
 │         │                     │──────────────────────►│ 前端给 open_note 注册了渲染器:
 │         │                     │                       │ 📖「正在打开《项目计划》…」+ 动画
 │         │                     │ 查完,返回笔记内容     │
 │         │◄────────────────────│  ←TOOL_CALL_RESULT→   │
 │         │                     │──────────────────────►│ ✅「已打开」+ 画出笔记卡片
 │         │ 拿到内容,总结        │                       │
 │◄────────│                     │                       │
```

🔑 工具是 **Claude 调的、后端执行的（读库）**，前端从头到尾**没碰执行**——它只是个「旁观者 +
美化者」：订阅 `TOOL_CALL_*` 事件，看到「open_note 被调了、参数 id=42、结果回来了」，据此把过程画好看。

**这条链 agui-server demo 已经跑通了雏形**——`▸ Bash Done` 卡片就是同一个机制（`Bash` 是后端执行的
工具，前端用默认渲染器画的卡片）。你的笔记场景只是：① 把 `Bash` 换成你注册的 `open_note`；② 把默认
卡片换成你的渲染器（📖 打开动画）。**现在就能做，不用碰 HITL 那套挂起回传。**

### 例子 B — HITL：完整双向，挂起回传（最难）

你说：「**帮我把 feat/test 这个分支删了。**」

```
你          Claude              后端(in-process MCP)        前端(CopilotKit)
 │  "删分支"  │                        │                         │
 │──────────►│ 想删但要确认            │                         │
 │           │ 调 confirm(            │                         │
 │           │   msg="确认删 feat/test?")                        │
 │           │───────────────────────►│ 这是"前端工具",不执行,  │
 │           │                        │ 转发出去 + handler 挂起  │
 │           │                        │────────(AG-UI)─────────►│ 按 confirm 渲染器画:
 │           │                        │   ...等待...            │ ┌─────────────┐
 │  看到卡片 │                        │                         │ │确认删除      │
 │◄──────────┼────────────────────────┼─────────────────────────┤ │feat/test?    │
 │  点[确认] │                        │                         │ │[确认] [取消] │
 │───────────┼────────────────────────┼────────(回传)──────────►│ └─────────────┘
 │           │                        │◄───{confirmed:true}─────│ 用户点了确认
 │           │ confirm 返回            │ 挂起的 handler 醒了,    │
 │           │◄───{confirmed:true}────│ 把结果还给 Claude       │
 │           │ → 真去删分支            │                         │
```

🔑 Claude 全程只当自己在调一个会返回值的函数 `confirm(msg) → {confirmed}`，它**不知道这个「函数」的
实现其实是「画个卡片给人看、等人点按钮」**。比例子 A/C 多了「暂停 + 等人 + 把人的选择回传」这一截——
这就是 HITL。

## 三、「那一层」到底是什么：三个映射

「执行 tool」和「渲染组件」之间那层东西，拆开看就是三个映射：

| Claude 眼里（抽象） | 前端眼里（具体） | 谁来连 |
|---|---|---|
| 工具**名** `confirm` / `open_note` | 渲染哪个**组件** | 前端注册表（工具名 → render） |
| 工具**参数** `{msg}` / `{noteId}` | 组件的 **props** | AG-UI 的 `TOOL_CALL_ARGS` |
| 工具**返回值** / **结果** | 用户**交互结果** / 展示数据 | AG-UI 回传 + 后端挂起的 handler（仅 HITL 需要） |

Claude 把工具当「会返回值的函数」；前端把「工具被调用」翻译成「渲染组件」、把「用户点击」翻译成
「函数返回值」。CopilotKit + AG-UI 就是这个双向翻译层。

## 四、HITL（Human-in-the-Loop）是什么

**人在回路 / 人工介入。** Agent 干活是个循环（想 → 调工具 → 看结果 → 再想…），HITL 就是在循环里插一个
「人的节点」：跑到关键步骤暂停，把控制权交回给人，等人做决定（确认 / 拒绝 / 选择 / 补信息），再继续。
「in the loop」= 人是这个执行循环的一环，而不是等 agent 全自动跑完才看结果。

- 例子 B（删分支确认）就是 HITL；工具执行前的权限审批弹窗也是 HITL。
- 和「纯展示」的区别：纯展示不用等人（去程半条链）；HITL 要等人 + 回传（完整双向链）。

## 五、三种形态 & 是不是一定要 tool

generative UI 有三种形态，**底层载体几乎都是 tool call**：

| 形态 | 是不是"业务 tool" | 底层触发 |
|---|---|---|
| ① 工具驱动（上面三个例子） | 是 | tool call |
| ② A2UI（声明式：agent 描述 UI 结构，前端用 catalog 渲染） | 不是业务工具 | CopilotKit 里仍靠一个特定工具触发（参数是 UI 声明）* |
| ③ 沙箱生成（LLM 现场生成 UI 代码，sandbox iframe 跑） | 不是 | `generateSandboxedUi` 这个工具（类型定义里有实证） |

**根因**：在 AG-UI 协议下，agent 能「主动让前端做事」的标准通道**只有 tool call**（加文本和 state）。
所以 A2UI / 沙箱看着不像传统工具，底层仍是「调一个特殊工具、参数是 UI 描述/代码」。

> **唯一真正不经过 tool 的是 shared state**：agent 更新 state（`STATE_DELTA`）→ 前端绑定 state 的
> 组件自动重渲染。但那叫 state-driven UI，严格说不算 generative UI（没"生成"新组件，只是数据变了重画）。

> \* 诚实标注：②A2UI「底层是不是一定通过工具」只对③沙箱有类型定义里的直接证据
> （`generateSandboxedUi` tool）；A2UI 是按「AG-UI 下 agent 主动通道只有 tool call」推断的，未逐行
> 验证 `@copilotkit/a2ui-renderer` 源码。

## 六、和 in-process MCP 的关系

不管前端工具还是后端工具，**给 Claude「注册工具」都走 in-process MCP**（`ClaudeAgent.tool()` /
`createSdkMcpServer`）——SDK 在 `initialize` 上报 `sdkMcpServers`、Claude 用 `mcp_message` control
请求回调 handler。区别只在 handler 里干什么：

- **后端工具**：handler 真干活（读写库），干完返回。
- **前端工具**：handler 不干活，发 AG-UI 事件给前端 + 挂起，等前端回传再返回。

> 顺带：VS Code 扩展「点图标」模式给 Claude 提供 IDE 能力（`openDiff`/`getDiagnostics`），用的就是
> 同一套 in-process MCP（扩展既 spawn claude、又是 MCP server 本体，复用 stdio 主管道的 `mcp_message`，
> 所以 `lsof` 看不到任何连接）；而终端 `/ide` 走的是 WebSocket MCP（claude 是终端独立进程、在扩展
> 外面，只能靠 `~/.claude/ide/<port>.lock` + ws 连进来）。**判据：谁 spawn 的 claude，谁就能给它塞
> in-process MCP。**

## 七、落到 agui-server：现在能做什么

| 想做的 | 现状 / 要补什么 | 难度 |
|---|---|---|
| 后端工具 + 前端自定义渲染（例子 C） | **现在就能做**：后端 `ClaudeAgent.tool()` 注册工具（handler 读写库）+ 前端 `renderToolCalls` 给工具名注册渲染器。demo 的 Bash 卡片已验证基础链 | ⭐ |
| 纯展示（例子 A） | 同上，甚至更简单（工具立即返回） | ⭐ |
| HITL / 前端工具（例子 B） | 要补双向链：后端收下的 `RunAgentInput.tools` 现在「接收但不使用」，要把它注册成 in-process MCP + handler 转发前端 + 挂起等回传 | ⭐⭐⭐ |
| 「正在读第 3 段」实时进度 | 工具执行时持续吐进度：SDK 的 `SdkToolProgressMessage` 转事件，或用 shared state 让 handler 写进度、前端订阅 | ⭐⭐ |

**一句话**：generative UI 的「生成」几乎总是搭在 tool call 上；Claude 能被注册任意工具（in-process
MCP），所以这些都能做——越往「前端执行 / 声明式 / 沙箱」走，要在 adapter 层发明的机制越多。最实用、
最该先做的是**例子 C（后端工具 + 前端渲染）**，因为它不需要回传、且 demo 已跑通雏形。
