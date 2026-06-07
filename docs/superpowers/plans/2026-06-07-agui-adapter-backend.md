# AG-UI 适配后端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `claude-agent-sdk-java` 仓库新增独立子项目 `agui-server`，把 `SdkMessage` 流翻译成 AG-UI 事件，经 HTTP+SSE 暴露，支持文本打字机 / 工具调用过程 / 思考占位 / 多轮 resume。

**Architecture:** Spring MVC + `SseEmitter`（核心库是 `Flow` 非 reactive，单订阅线程推送最简单）。核心翻译逻辑 `RunTranslator implements Flow.Subscriber<SdkMessage>`，写入一个 `Consumer<AgUiEvent>` sink——sink 由 Controller 接到 SseEmitter，使翻译逻辑脱离 HTTP 可单元测试。会话用核心库的 `Options.resume(sessionId)` + 内存 `SessionStore`。核心模块一行不改，`agui-server` 按 Maven 坐标依赖它。

**Tech Stack:** Java 17, Spring Boot 3.x (spring-boot-starter-web), Jackson, JUnit 4, claude-agent-sdk-java 0.1.0-SNAPSHOT。

**关联 spec:** `docs/superpowers/specs/2026-06-07-agui-adapter-design.md`

**范围（本 plan）:** 仅后端。前端 CopilotKit demo 是独立后续 plan。不做 shared state / generative UI / HITL。

---

## 文件结构

```
agui-server/
├── pom.xml                                            # 独立 Spring Boot 项目，坐标依赖核心库
├── src/main/resources/application.properties          # 端口 8095 + 配置
└── src/main/java/com/anthropic/claude/agent/agui/
    ├── AgUiServerApplication.java                      # Spring Boot 入口
    ├── event/AgUiEvent.java                            # sealed interface + record 子类型（AG-UI 事件模型）
    ├── model/RunAgentInput.java                        # 请求体 + lastUserText()
    ├── SessionStore.java                               # threadId → claude sessionId 内存 map
    ├── RunTranslator.java                              # Flow.Subscriber<SdkMessage> 状态机（核心）
    └── AgUiController.java                             # POST /agui → SseEmitter
src/test/java/com/anthropic/claude/agent/agui/
    ├── AgUiEventTest.java
    ├── RunAgentInputTest.java
    ├── SessionStoreTest.java
    └── RunTranslatorTest.java
```

职责边界：
- `AgUiEvent`：纯数据 + 序列化形状。
- `RunTranslator`：把 SDK 消息翻译成事件（无 HTTP 依赖，可测）。
- `AgUiController`：HTTP/SSE 编排 + 启动 query + 超时守护。
- `SessionStore`：会话映射。

---

## Task 0: 前置确认（不写代码，记录结论）

**目的:** 核心库装进本地仓库；确认 plan 中用到的几个 builder 方法存在（避免编译错）。

- [ ] **Step 1: 核心库 install 到本地 ~/.m2**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java && ~/dev/apache-maven-3.9.14/bin/mvn -q install -DskipTests
```
Expected: BUILD SUCCESS，`~/.m2/repository/com/anthropic/claude/claude-agent-sdk-java/0.1.0-SNAPSHOT/` 下有 jar。

- [ ] **Step 2: 确认 Options.Builder 的方法名**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java && grep -nE "public Builder (includePartialMessages|model|unsetEnv|env|resume|permissionMode)\b" src/main/java/com/anthropic/claude/agent/Options.java
```
Expected: 至少看到 `resume`、`permissionMode`、`model`、`includePartialMessages`。
**记录结论**（影响 Task 9 的 Options 构造）：
- 若有 `unsetEnv(String...)` → Task 9 用 `.unsetEnv("CLAUDECODE","CLAUDE_CODE_ENTRYPOINT","CLAUDE_CODE_EXECPATH","TMUX")`。
- 否则若 `env(String,String)` 接受 null 值（devdocs 0604 §8 commit 7ca44a5：`env(key,null)` = unset）→ 改用 `.env("CLAUDECODE", null)` 等四行。
- 两者都没有 → Task 9 先不剥离（标记 TODO，生产由 launchd 启动本就无这些变量）。

- [ ] **Step 3: 确认 partial stream-event 的 wire 结构**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java && find src/test -name '*.json' | xargs grep -l 'stream_event\|content_block_delta' 2>/dev/null | head; echo '---'; find src/test -name '*stream*'
```
Expected: 找到含 `content_block_delta` 的 fixture。打开确认内层结构符合 Anthropic 标准：
`content_block_start` 带 `content_block.{type,id,name}`；`content_block_delta` 带 `delta.{type: text_delta|input_json_delta|thinking_delta|signature_delta, text|partial_json|thinking|signature}`；`content_block_stop` 带 `index`。
（plan 后续代码按此标准结构解析；若 fixture 有出入，以 fixture 为准微调取字段路径。）

---

## Task 1: 子项目脚手架

**Files:**
- Create: `agui-server/pom.xml`
- Create: `agui-server/src/main/java/com/anthropic/claude/agent/agui/AgUiServerApplication.java`
- Create: `agui-server/src/main/resources/application.properties`

- [ ] **Step 1: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.anthropic.claude</groupId>
    <artifactId>claude-agent-sdk-agui-server</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.anthropic.claude</groupId>
            <artifactId>claude-agent-sdk-java</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- Spring Boot 3 的 starter-test 只带 JUnit 5；本项目用 JUnit 4，需 vintage engine 才能被 surefire 发现执行 -->
        <dependency>
            <groupId>org.junit.vintage</groupId>
            <artifactId>junit-vintage-engine</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```
注：spring-boot-starter-test 默认带 JUnit 5；本项目用 JUnit 4，显式加 junit:junit 依赖（version 由 spring-boot parent 管理），测试用 `org.junit.Test`。

- [ ] **Step 2: 写 application.properties**

```properties
server.port=8095
# claude 配置
agui.claude.model=sonnet
agui.claude.timeout-seconds=120
```

- [ ] **Step 3: 写 AgUiServerApplication**

```java
package com.anthropic.claude.agent.agui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgUiServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgUiServerApplication.class, args);
    }
}
```

- [ ] **Step 4: 编译验证**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q compile
```
Expected: BUILD SUCCESS（核心库已在 Task 0 install）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add agui-server/pom.xml agui-server/src/main/java agui-server/src/main/resources && git commit -m "feat(agui): scaffold agui-server spring boot subproject"
```

---

## Task 2: AgUiEvent 事件模型

**Files:**
- Create: `agui-server/src/main/java/com/anthropic/claude/agent/agui/event/AgUiEvent.java`
- Test: `agui-server/src/test/java/com/anthropic/claude/agent/agui/AgUiEventTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class AgUiEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void textContentSerializesWithTypeAndFields() throws Exception {
        AgUiEvent e = new AgUiEvent.TextMessageContent("m1", "hello");
        String json = mapper.writeValueAsString(e);
        assertTrue(json, json.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""));
        assertTrue(json, json.contains("\"messageId\":\"m1\""));
        assertTrue(json, json.contains("\"delta\":\"hello\""));
    }

    @Test
    public void runStartedHasType() throws Exception {
        AgUiEvent e = new AgUiEvent.RunStarted("t1", "r1");
        String json = mapper.writeValueAsString(e);
        assertTrue(json, json.contains("\"type\":\"RUN_STARTED\""));
        assertTrue(json, json.contains("\"threadId\":\"t1\""));
        assertTrue(json, json.contains("\"runId\":\"r1\""));
    }

    @Test
    public void typeAccessorMatchesWire() {
        assertEquals("TOOL_CALL_START", new AgUiEvent.ToolCallStart("c1", "Read").type());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=AgUiEventTest
```
Expected: 编译失败（AgUiEvent 不存在）。

- [ ] **Step 3: 写 AgUiEvent**

```java
package com.anthropic.claude.agent.agui.event;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AG-UI 标准事件模型（子集）。每个 record 序列化为一条 SSE data JSON，含 "type" 字段（SNAKE_CASE）
 * 与 camelCase 字段。事件名/字段以 AG-UI spec 为准（实现阶段已对照）。
 */
public sealed interface AgUiEvent {

    @JsonProperty("type")
    String type();

    record RunStarted(String threadId, String runId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "RUN_STARTED"; }
    }
    record RunFinished(String threadId, String runId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "RUN_FINISHED"; }
    }
    record RunError(String message, String code) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "RUN_ERROR"; }
    }
    record TextMessageStart(String messageId, String role) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TEXT_MESSAGE_START"; }
    }
    record TextMessageContent(String messageId, String delta) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TEXT_MESSAGE_CONTENT"; }
    }
    record TextMessageEnd(String messageId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TEXT_MESSAGE_END"; }
    }
    record ToolCallStart(String toolCallId, String toolCallName) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TOOL_CALL_START"; }
    }
    record ToolCallArgs(String toolCallId, String delta) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TOOL_CALL_ARGS"; }
    }
    record ToolCallEnd(String toolCallId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TOOL_CALL_END"; }
    }
    record ToolCallResult(String messageId, String toolCallId, String content) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "TOOL_CALL_RESULT"; }
    }
    record ReasoningStart(String messageId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "REASONING_START"; }
    }
    record ReasoningMessageStart(String messageId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "REASONING_MESSAGE_START"; }
    }
    record ReasoningMessageContent(String messageId, String delta) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "REASONING_MESSAGE_CONTENT"; }
    }
    record ReasoningMessageEnd(String messageId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "REASONING_MESSAGE_END"; }
    }
    record ReasoningEnd(String messageId) implements AgUiEvent {
        @Override @JsonProperty("type") public String type() { return "REASONING_END"; }
    }
}
```
> 实现阶段验证点：AG-UI 事件名/字段对照 `@ag-ui/core` spec；若字段名有差异（如 `messageId` vs `message_id`），统一在此文件调整，测试同步改。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=AgUiEventTest
```
Expected: PASS（3 tests）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add agui-server/src/main/java/com/anthropic/claude/agent/agui/event agui-server/src/test/java/com/anthropic/claude/agent/agui/AgUiEventTest.java && git commit -m "feat(agui): add AgUiEvent model with json serialization"
```

---

## Task 3: RunAgentInput

**Files:**
- Create: `agui-server/src/main/java/com/anthropic/claude/agent/agui/model/RunAgentInput.java`
- Test: `agui-server/src/test/java/com/anthropic/claude/agent/agui/RunAgentInputTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.anthropic.claude.agent.agui.model.RunAgentInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class RunAgentInputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void lastUserTextReturnsLatestUserMessage() throws Exception {
        String json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":["
                + "{\"id\":\"1\",\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"id\":\"2\",\"role\":\"assistant\",\"content\":\"hello\"},"
                + "{\"id\":\"3\",\"role\":\"user\",\"content\":\"summarize README\"}]}";
        RunAgentInput in = mapper.readValue(json, RunAgentInput.class);
        assertEquals("summarize README", in.lastUserText());
        assertEquals("t1", in.threadId());
    }

    @Test
    public void lastUserTextNullWhenNoUserMessage() throws Exception {
        String json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":["
                + "{\"id\":\"1\",\"role\":\"assistant\",\"content\":\"hello\"}]}";
        RunAgentInput in = mapper.readValue(json, RunAgentInput.class);
        assertNull(in.lastUserText());
    }

    @Test
    public void toleratesUnknownFields() throws Exception {
        String json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"tools\":[],\"state\":{},"
                + "\"context\":[],\"messages\":[{\"id\":\"1\",\"role\":\"user\",\"content\":\"hi\"}]}";
        RunAgentInput in = mapper.readValue(json, RunAgentInput.class);
        assertEquals("hi", in.lastUserText());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunAgentInputTest
```
Expected: 编译失败（RunAgentInput 不存在）。

- [ ] **Step 3: 写 RunAgentInput**

```java
package com.anthropic.claude.agent.agui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** AG-UI 前端 POST 的运行输入；只取需要的字段，其余忽略（为后续 HITL/state 预留）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunAgentInput(String threadId, String runId, List<Message> messages) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String role, String content) {}

    /** 最后一条 role=user 的 content；无则 null。 */
    public String lastUserText() {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && "user".equals(m.role())) {
                return m.content();
            }
        }
        return null;
    }
}
```
> 实现阶段验证点：AG-UI 的 message.content 可能是结构化数组而非纯字符串；若前端发数组，这里 content 类型改 JsonNode 并在 lastUserText() 抽取 text。先按字符串实现，Task 10 真连前端时确认。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunAgentInputTest
```
Expected: PASS（3 tests）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add agui-server/src/main/java/com/anthropic/claude/agent/agui/model agui-server/src/test/java/com/anthropic/claude/agent/agui/RunAgentInputTest.java && git commit -m "feat(agui): add RunAgentInput with lastUserText"
```

---

## Task 4: SessionStore

**Files:**
- Create: `agui-server/src/main/java/com/anthropic/claude/agent/agui/SessionStore.java`
- Test: `agui-server/src/test/java/com/anthropic/claude/agent/agui/SessionStoreTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SessionStoreTest {

    @Test
    public void firstTurnHasNoSession() {
        SessionStore store = new SessionStore();
        assertNull(store.get("thread-A"));
    }

    @Test
    public void putThenGetReturnsSessionId() {
        SessionStore store = new SessionStore();
        store.put("thread-A", "sess-123");
        assertEquals("sess-123", store.get("thread-A"));
    }

    @Test
    public void perThreadIsolation() {
        SessionStore store = new SessionStore();
        store.put("thread-A", "sess-A");
        store.put("thread-B", "sess-B");
        assertEquals("sess-A", store.get("thread-A"));
        assertEquals("sess-B", store.get("thread-B"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=SessionStoreTest
```
Expected: 编译失败（SessionStore 不存在）。

- [ ] **Step 3: 写 SessionStore**

```java
package com.anthropic.claude.agent.agui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** threadId → claude sessionId 的内存映射（demo 级；无持久化）。 */
@Component
public class SessionStore {

    private final Map<String, String> threadToSession = new ConcurrentHashMap<>();

    /** 已有会话的 claude sessionId；首轮返回 null。 */
    public String get(String threadId) {
        return threadId == null ? null : threadToSession.get(threadId);
    }

    public void put(String threadId, String sessionId) {
        if (threadId != null && sessionId != null) {
            threadToSession.put(threadId, sessionId);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=SessionStoreTest
```
Expected: PASS（3 tests）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add agui-server/src/main/java/com/anthropic/claude/agent/agui/SessionStore.java agui-server/src/test/java/com/anthropic/claude/agent/agui/SessionStoreTest.java && git commit -m "feat(agui): add in-memory SessionStore"
```

---

## Task 5: RunTranslator — 骨架 + 文本流

**Files:**
- Create: `agui-server/src/main/java/com/anthropic/claude/agent/agui/RunTranslator.java`
- Test: `agui-server/src/test/java/com/anthropic/claude/agent/agui/RunTranslatorTest.java`

`RunTranslator` 是 `Flow.Subscriber<SdkMessage>`，把消息翻译成 `AgUiEvent` 写入 sink。本任务先做：RUN_STARTED（由 Controller 在订阅前发，故不在 translator）、文本块 start/delta/stop，以及终结时由 onComplete 不处理（终结在 Task 8 的 SdkResultMessage）。本任务聚焦 partial 文本。

- [ ] **Step 1: 写失败测试（文本流）**

```java
package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;

import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.anthropic.claude.agent.message.SdkPartialAssistantMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RunTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造一个 partial（stream_event）消息：event 为给定 JSON。 */
    private SdkPartialAssistantMessage partial(String eventJson) throws Exception {
        var event = mapper.readTree(eventJson);
        return new SdkPartialAssistantMessage(event, null, "sess-1", "uuid-1", event);
    }

    private RunTranslator newTranslator(List<AgUiEvent> sink, SessionStore store) {
        return new RunTranslator(sink::add, store, "thread-1", "run-1");
    }

    @Test
    public void textBlockProducesStartContentEnd() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}"));
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(4, out.size());
        AgUiEvent.TextMessageStart start = (AgUiEvent.TextMessageStart) out.get(0);
        assertEquals("assistant", start.role());
        AgUiEvent.TextMessageContent c1 = (AgUiEvent.TextMessageContent) out.get(1);
        assertEquals("Hel", c1.delta());
        assertEquals(start.messageId(), c1.messageId());           // 同一 messageId 串联
        AgUiEvent.TextMessageContent c2 = (AgUiEvent.TextMessageContent) out.get(2);
        assertEquals("lo", c2.delta());
        AgUiEvent.TextMessageEnd end = (AgUiEvent.TextMessageEnd) out.get(3);
        assertEquals(start.messageId(), end.messageId());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: 编译失败（RunTranslator 不存在）。

- [ ] **Step 3: 写 RunTranslator（骨架 + 文本）**

```java
package com.anthropic.claude.agent.agui;

import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkPartialAssistantMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 把 claude SDK 的 {@link SdkMessage} 流翻译成 {@link AgUiEvent}，写入 sink。无 HTTP 依赖，可单测。
 * 状态机：按 content block 的 index 跟踪当前打开的块（text / tool_use / thinking）。
 */
public class RunTranslator implements Flow.Subscriber<SdkMessage> {

    private enum Kind { TEXT, TOOL, THINKING }

    private record OpenBlock(Kind kind, String id) {}

    private final Consumer<AgUiEvent> sink;
    private final SessionStore sessionStore;
    private final String threadId;
    private final String runId;
    private final AtomicInteger msgSeq = new AtomicInteger();
    private final Map<Integer, OpenBlock> openBlocks = new HashMap<>();

    private Flow.Subscription subscription;

    public RunTranslator(Consumer<AgUiEvent> sink, SessionStore sessionStore,
                         String threadId, String runId) {
        this.sink = sink;
        this.sessionStore = sessionStore;
        this.threadId = threadId;
        this.runId = runId;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(Long.MAX_VALUE);   // 无背压：全要（SSE 推送跟得上）
    }

    @Override
    public void onNext(SdkMessage msg) {
        if (msg instanceof SdkPartialAssistantMessage p) {
            handlePartial(p);
        }
        // SdkUserMessage（tool_result）→ Task 6；SdkResultMessage（终结）→ Task 8
    }

    @Override
    public void onError(Throwable t) {
        // Task 8 填充
    }

    @Override
    public void onComplete() {
        // 终结由 SdkResultMessage 驱动（Task 8）；此处无操作
    }

    private void handlePartial(SdkPartialAssistantMessage p) {
        JsonNode event = p.event();
        String evType = text(event, "type");
        if (evType == null) {
            return;
        }
        int index = event.path("index").asInt(-1);
        switch (evType) {
            case "content_block_start" -> onBlockStart(index, event.path("content_block"));
            case "content_block_delta" -> onBlockDelta(index, event.path("delta"));
            case "content_block_stop" -> onBlockStop(index);
            default -> { /* message_start/delta/stop 等忽略 */ }
        }
    }

    private void onBlockStart(int index, JsonNode block) {
        String blockType = text(block, "type");
        if ("text".equals(blockType)) {
            String id = "msg-" + msgSeq.incrementAndGet();
            openBlocks.put(index, new OpenBlock(Kind.TEXT, id));
            sink.accept(new AgUiEvent.TextMessageStart(id, "assistant"));
        }
        // tool_use → Task 6；thinking → Task 7
    }

    private void onBlockDelta(int index, JsonNode delta) {
        OpenBlock open = openBlocks.get(index);
        if (open == null) {
            return;
        }
        String deltaType = text(delta, "type");
        if (open.kind() == Kind.TEXT && "text_delta".equals(deltaType)) {
            sink.accept(new AgUiEvent.TextMessageContent(open.id(), text(delta, "text")));
        }
        // input_json_delta → Task 6；thinking_delta/signature_delta → Task 7
    }

    private void onBlockStop(int index) {
        OpenBlock open = openBlocks.remove(index);
        if (open == null) {
            return;
        }
        if (open.kind() == Kind.TEXT) {
            sink.accept(new AgUiEvent.TextMessageEnd(open.id()));
        }
        // TOOL/THINKING → Task 6/7
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: PASS（1 test）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add agui-server/src/main/java/com/anthropic/claude/agent/agui/RunTranslator.java agui-server/src/test/java/com/anthropic/claude/agent/agui/RunTranslatorTest.java && git commit -m "feat(agui): RunTranslator skeleton with text streaming"
```

---

## Task 6: RunTranslator — 工具调用

**Files:**
- Modify: `agui-server/src/main/java/com/anthropic/claude/agent/agui/RunTranslator.java`
- Modify: `agui-server/src/test/java/com/anthropic/claude/agent/agui/RunTranslatorTest.java`

- [ ] **Step 1: 加失败测试（工具调用 + 结果）**

在 `RunTranslatorTest` 增加 imports 与测试：
```java
// 顶部 imports 追加：
import com.anthropic.claude.agent.message.ApiMessage;
import com.anthropic.claude.agent.message.ContentBlock;
import com.anthropic.claude.agent.message.SdkUserMessage;
import java.util.List;
```
```java
    @Test
    public void toolUseProducesStartArgsEnd() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"file\\\":\"}}"));
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(3, out.size());
        AgUiEvent.ToolCallStart start = (AgUiEvent.ToolCallStart) out.get(0);
        assertEquals("toolu_1", start.toolCallId());
        assertEquals("Read", start.toolCallName());
        AgUiEvent.ToolCallArgs args = (AgUiEvent.ToolCallArgs) out.get(1);
        assertEquals("toolu_1", args.toolCallId());
        assertEquals("{\"file\":", args.delta());
        AgUiEvent.ToolCallEnd end = (AgUiEvent.ToolCallEnd) out.get(2);
        assertEquals("toolu_1", end.toolCallId());
    }

    @Test
    public void toolResultFromUserMessageProducesResult() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        var contentNode = mapper.readTree("\"file contents here\"");
        ContentBlock.ToolResult tr =
                new ContentBlock.ToolResult("toolu_1", contentNode, false, contentNode);
        ApiMessage apiMsg = new ApiMessage("user", null, null, List.of(tr), null, null, null);
        SdkUserMessage um = new SdkUserMessage("u1", "sess-1", null, apiMsg, null);

        t.onNext(um);

        assertEquals(1, out.size());
        AgUiEvent.ToolCallResult r = (AgUiEvent.ToolCallResult) out.get(0);
        assertEquals("toolu_1", r.toolCallId());
        assertEquals("file contents here", r.content());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: 2 个新测试失败（tool_use 当前未处理，无 SdkUserMessage 分支）。

- [ ] **Step 3: 在 RunTranslator 加工具分支**

在 `onNext` 加 SdkUserMessage 分支（imports 顶部追加）：
```java
import com.anthropic.claude.agent.message.ApiMessage;
import com.anthropic.claude.agent.message.ContentBlock;
import com.anthropic.claude.agent.message.SdkUserMessage;
```
```java
    @Override
    public void onNext(SdkMessage msg) {
        if (msg instanceof SdkPartialAssistantMessage p) {
            handlePartial(p);
        } else if (msg instanceof SdkUserMessage u) {
            handleUser(u);
        }
        // SdkResultMessage（终结）→ Task 8
    }

    private void handleUser(SdkUserMessage u) {
        ApiMessage m = u.message();
        if (m == null || m.content() == null) {
            return;
        }
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.ToolResult tr) {
                String content = tr.content() == null ? "" : tr.content().asText("");
                sink.accept(new AgUiEvent.ToolCallResult(
                        "msg-" + msgSeq.incrementAndGet(), tr.toolUseId(), content));
            }
        }
    }
```
在 `onBlockStart` 的 text 分支后加 tool_use：
```java
        if ("tool_use".equals(blockType)) {
            String id = text(block, "id");
            openBlocks.put(index, new OpenBlock(Kind.TOOL, id));
            sink.accept(new AgUiEvent.ToolCallStart(id, text(block, "name")));
        }
```
在 `onBlockDelta` 加 input_json_delta：
```java
        if (open.kind() == Kind.TOOL && "input_json_delta".equals(deltaType)) {
            sink.accept(new AgUiEvent.ToolCallArgs(open.id(), text(delta, "partial_json")));
        }
```
在 `onBlockStop` 加 TOOL：
```java
        if (open.kind() == Kind.TOOL) {
            sink.accept(new AgUiEvent.ToolCallEnd(open.id()));
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: PASS（3 tests）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add -A && git commit -m "feat(agui): RunTranslator tool call + tool result"
```

---

## Task 7: RunTranslator — 思考占位

**Files:**
- Modify: `RunTranslator.java`, `RunTranslatorTest.java`

- [ ] **Step 1: 加失败测试（思考）**

```java
    @Test
    public void thinkingBlockProducesReasoningEvents() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"let me think\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc==\"}}")); // 忽略
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(4, out.size());   // START, MESSAGE_START, MESSAGE_CONTENT, (MESSAGE_END+END 合并? 见下)
        // 期望序列：ReasoningStart, ReasoningMessageStart, ReasoningMessageContent, ReasoningMessageEnd+ReasoningEnd
    }
```
> 注：stop 时要发 ReasoningMessageEnd + ReasoningEnd 两个事件，所以总数应为 5。把上面 assertEquals(4,...) 改为按实现期望写，先断言关键事件类型与内容：
```java
    @Test
    public void thinkingBlockProducesReasoningEvents() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"let me think\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc==\"}}"));
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(5, out.size());
        org.junit.Assert.assertTrue(out.get(0) instanceof AgUiEvent.ReasoningStart);
        org.junit.Assert.assertTrue(out.get(1) instanceof AgUiEvent.ReasoningMessageStart);
        AgUiEvent.ReasoningMessageContent c = (AgUiEvent.ReasoningMessageContent) out.get(2);
        assertEquals("let me think", c.delta());           // signature_delta 不产出事件
        org.junit.Assert.assertTrue(out.get(3) instanceof AgUiEvent.ReasoningMessageEnd);
        org.junit.Assert.assertTrue(out.get(4) instanceof AgUiEvent.ReasoningEnd);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest#thinkingBlockProducesReasoningEvents
```
Expected: FAIL（thinking 未处理，out 为空）。

- [ ] **Step 3: 在 RunTranslator 加思考分支**

`onBlockStart` 加 thinking：
```java
        if ("thinking".equals(blockType) || "redacted_thinking".equals(blockType)) {
            String id = "reason-" + msgSeq.incrementAndGet();
            openBlocks.put(index, new OpenBlock(Kind.THINKING, id));
            sink.accept(new AgUiEvent.ReasoningStart(id));
            sink.accept(new AgUiEvent.ReasoningMessageStart(id));
        }
```
`onBlockDelta` 加 thinking_delta（signature_delta 不处理 = 忽略）：
```java
        if (open.kind() == Kind.THINKING && "thinking_delta".equals(deltaType)) {
            sink.accept(new AgUiEvent.ReasoningMessageContent(open.id(), text(delta, "thinking")));
        }
```
`onBlockStop` 加 THINKING：
```java
        if (open.kind() == Kind.THINKING) {
            sink.accept(new AgUiEvent.ReasoningMessageEnd(open.id()));
            sink.accept(new AgUiEvent.ReasoningEnd(open.id()));
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: PASS（4 tests）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add -A && git commit -m "feat(agui): RunTranslator thinking/reasoning placeholder"
```

---

## Task 8: RunTranslator — 终结（result / error）

**Files:**
- Modify: `RunTranslator.java`, `RunTranslatorTest.java`

- [ ] **Step 1: 加失败测试（result 成功 / 错误 / onError）**

```java
    @Test
    public void resultSuccessStoresSessionAndFinishes() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        SessionStore store = new SessionStore();
        RunTranslator t = newTranslator(out, store);

        var result = new com.anthropic.claude.agent.message.SdkResultMessage(
                com.anthropic.claude.agent.message.SdkResultMessage.Subtype.SUCCESS,
                false, "done", "sess-xyz", "u1", 1, 10L, 5L, 0.01, "end_turn", null, null,
                mapper.createObjectNode());
        t.onNext(result);

        assertEquals(1, out.size());
        org.junit.Assert.assertTrue(out.get(0) instanceof AgUiEvent.RunFinished);
        assertEquals("sess-xyz", store.get("thread-1"));    // 会话被记下供下轮 resume
    }

    @Test
    public void resultErrorProducesRunError() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        var result = new com.anthropic.claude.agent.message.SdkResultMessage(
                com.anthropic.claude.agent.message.SdkResultMessage.Subtype.ERROR_MAX_TURNS,
                true, null, "sess-err", "u1", 5, 1L, 1L, 0.0, null, null, null,
                mapper.createObjectNode());
        t.onNext(result);

        assertEquals(1, out.size());
        AgUiEvent.RunError e = (AgUiEvent.RunError) out.get(0);
        assertEquals("error_max_turns", e.code());
    }

    @Test
    public void onErrorProducesRunError() {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());
        t.onError(new RuntimeException("boom"));
        assertEquals(1, out.size());
        AgUiEvent.RunError e = (AgUiEvent.RunError) out.get(0);
        org.junit.Assert.assertTrue(e.message().contains("boom"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: 3 个新测试失败。

- [ ] **Step 3: 在 RunTranslator 加终结分支**

imports 追加：
```java
import com.anthropic.claude.agent.message.SdkResultMessage;
```
`onNext` 加 result 分支：
```java
        } else if (msg instanceof SdkResultMessage r) {
            handleResult(r);
        }
```
新增方法：
```java
    private void handleResult(SdkResultMessage r) {
        if (r.sessionId() != null) {
            sessionStore.put(threadId, r.sessionId());
        }
        if (r.isError() || r.subtype() != SdkResultMessage.Subtype.SUCCESS) {
            String code = r.subtype() == null ? "error" : r.subtype().wire();
            sink.accept(new AgUiEvent.RunError("claude run error: " + code, code));
        } else {
            sink.accept(new AgUiEvent.RunFinished(threadId, runId));
        }
    }
```
`onError` 实现：
```java
    @Override
    public void onError(Throwable t) {
        sink.accept(new AgUiEvent.RunError(String.valueOf(t.getMessage()), "exception"));
    }
```

- [ ] **Step 4: 跑全部 RunTranslator 测试确认通过**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q test -Dtest=RunTranslatorTest
```
Expected: PASS（7 tests）。

- [ ] **Step 5: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add -A && git commit -m "feat(agui): RunTranslator terminal result/error handling"
```

---

## Task 9: AgUiController（SSE 编排）

**Files:**
- Create: `agui-server/src/main/java/com/anthropic/claude/agent/agui/AgUiController.java`

无独立单测（编排逻辑，靠 Task 10 集成验证）；编译 + 启动即验证。

- [ ] **Step 1: 写 AgUiController**

```java
package com.anthropic.claude.agent.agui;

import com.anthropic.claude.agent.ClaudeAgent;
import com.anthropic.claude.agent.Options;
import com.anthropic.claude.agent.Query;
import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.anthropic.claude.agent.agui.model.RunAgentInput;
import com.anthropic.claude.agent.permission.PermissionMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@CrossOrigin                       // demo：允许前端 dev server 跨域；生产再收紧
public class AgUiController {

    private static final Logger log = LoggerFactory.getLogger(AgUiController.class);

    private final SessionStore sessionStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService timeoutPool =
            Executors.newSingleThreadScheduledExecutor();

    @Value("${agui.claude.model:sonnet}")
    private String model;
    @Value("${agui.claude.timeout-seconds:120}")
    private long timeoutSeconds;

    public AgUiController(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @PostMapping(path = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody RunAgentInput input) {
        String threadId = input.threadId() != null ? input.threadId() : "thread-" + System.nanoTime();
        String runId = input.runId() != null ? input.runId() : "run-" + System.nanoTime();
        String prompt = input.lastUserText();

        SseEmitter emitter = new SseEmitter(timeoutSeconds * 2 * 1000);
        Consumer<AgUiEvent> sink = e -> send(emitter, e);

        if (prompt == null || prompt.isBlank()) {
            sink.accept(new AgUiEvent.RunError("no user message in input", "bad_request"));
            emitter.complete();
            return emitter;
        }

        sink.accept(new AgUiEvent.RunStarted(threadId, runId));

        String resumeSid = sessionStore.get(threadId);
        Options.Builder ob = Options.builder()
                .includePartialMessages(true)
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .model(model);
        if (resumeSid != null) {
            ob.resume(resumeSid);
        }
        // Task 0 结论：按支持情况二选一剥离 CLAUDECODE 等环境变量
        // ob.unsetEnv("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH", "TMUX");
        Options options = ob.build();

        log.info("agui run thread={} run={} resume={} promptLen={}",
                threadId, runId, resumeSid != null, prompt.length());

        Query query;
        try {
            query = ClaudeAgent.query(prompt, options);
        } catch (RuntimeException ex) {
            log.error("failed to start claude query", ex);
            sink.accept(new AgUiEvent.RunError(String.valueOf(ex.getMessage()), "spawn_failed"));
            emitter.complete();
            return emitter;
        }

        RunTranslator translator = new RunTranslator(sink, sessionStore, threadId, runId) {
            @Override public void onComplete() { emitter.complete(); }
            @Override public void onError(Throwable t) { super.onError(t); emitter.complete(); }
        };

        // 超时守护：到点强制 close + 完成
        var timeoutTask = timeoutPool.schedule(() -> {
            log.warn("agui run timeout thread={} run={}", threadId, runId);
            sink.accept(new AgUiEvent.RunError("run timed out", "timeout"));
            query.close();
            emitter.complete();
        }, timeoutSeconds, TimeUnit.SECONDS);

        emitter.onCompletion(() -> { timeoutTask.cancel(false); query.close(); });
        emitter.onError(t -> { timeoutTask.cancel(false); query.close(); });
        emitter.onTimeout(() -> { query.close(); emitter.complete(); });

        query.subscribe(translator);
        return emitter;
    }

    private void send(SseEmitter emitter, AgUiEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(mapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("sse send failed (client gone?) type={}", event.type());
        }
    }
}
```
> 实现阶段验证点（Task 0 结论落地）：取消注释那行 `unsetEnv(...)`，或改 `env(key,null)`，或保持注释。
> `RunFinished` 后需 complete emitter：上面用匿名子类覆盖 onComplete——但 translator 的 onComplete 由 SDK 在流结束时调；RunFinished 事件在 handleResult 已发，流随后 complete 触发 emitter.complete()。若 SDK 在 result 后不调 onComplete，则靠超时守护兜底（可在 handleResult 后也 complete，Task 10 据实际行为决定）。

- [ ] **Step 2: 编译 + 启动验证**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn -q compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
cd ~/dev/java/claude-agent-sdk-java && git add agui-server/src/main/java/com/anthropic/claude/agent/agui/AgUiController.java && git commit -m "feat(agui): AgUiController SSE endpoint with timeout guard"
```

---

## Task 10: 真 claude 集成验证（手动）

**目的:** 端到端确认 SSE 事件流正确。

- [ ] **Step 1: 启动服务**

Run:
```bash
cd ~/dev/java/claude-agent-sdk-java/agui-server && ~/dev/apache-maven-3.9.14/bin/mvn spring-boot:run
```
Expected: 启动日志，监听 8095。

- [ ] **Step 2: curl 打一轮（无工具的纯文本）**

新终端 Run:
```bash
curl --noproxy '*' -N -s -X POST http://localhost:8095/agui \
  -H 'Content-Type: application/json' \
  -d '{"threadId":"t-demo","runId":"r1","messages":[{"id":"1","role":"user","content":"用一句话介绍你自己"}]}'
```
Expected: SSE 流，依次出现 `RUN_STARTED` → `TEXT_MESSAGE_START` → 多个 `TEXT_MESSAGE_CONTENT` → `TEXT_MESSAGE_END` → `RUN_FINISHED`。

- [ ] **Step 3: curl 打一轮带工具（触发 Read/Bash）**

```bash
curl --noproxy '*' -N -s -X POST http://localhost:8095/agui \
  -H 'Content-Type: application/json' \
  -d '{"threadId":"t-demo2","runId":"r1","messages":[{"id":"1","role":"user","content":"列出当前目录的文件"}]}'
```
Expected: 出现 `TOOL_CALL_START`（name=Bash 或 LS）→ `TOOL_CALL_ARGS` → `TOOL_CALL_END` → `TOOL_CALL_RESULT` → 文本 → `RUN_FINISHED`。

- [ ] **Step 4: 验证多轮 resume**

先发第一轮（记 threadId=t-multi），claude 回答后再发第二轮同 threadId 引用上文：
```bash
curl --noproxy '*' -N -s -X POST http://localhost:8095/agui -H 'Content-Type: application/json' \
  -d '{"threadId":"t-multi","runId":"r1","messages":[{"id":"1","role":"user","content":"记住数字 42"}]}'
curl --noproxy '*' -N -s -X POST http://localhost:8095/agui -H 'Content-Type: application/json' \
  -d '{"threadId":"t-multi","runId":"r2","messages":[{"id":"2","role":"user","content":"我让你记的数字是多少"}]}'
```
Expected: 第二轮回答含 "42"（说明 resume 生效，服务端日志 `resume=true`）。

- [ ] **Step 5: 记录结论 + 修正**

把观测到的实际事件序列与 §6 映射对照；若 SDK 在 result 后是否调 onComplete、content 是否结构化等与假设不符，回到对应 Task 修正并补测试。结论追加到 spec 的「待验证清单」。

---

## Self-Review（已执行）

- **Spec coverage:** 范围(§2)→Task 5-8；架构(§3)→Task 9；目录(§4)→Task 1；组件(§5)→Task 2/3/4/5/9；事件映射(§6)→Task 5/6/7/8；思考边界(§7)→Task 7；选型(§8)→Task 1/9；错误处理(§9)→Task 8/9；测试(§10)→各 Task TDD + Task 10；配置(§11)→Task 1。前端(spec 提及)→独立后续 plan。✅ 后端全覆盖。
- **Placeholder scan:** 无 TBD/TODO 占位；"实现阶段验证点"为带具体动作的核对项，非空白。Task 0 把外部不确定性（builder 方法名、wire 结构）转成可执行确认步骤。
- **Type consistency:** `RunTranslator(Consumer<AgUiEvent>, SessionStore, String, String)` 构造签名在 Task 5/6/7/8/9 一致；`msgSeq`（注意 Task 5 Step 3 标注的 ASCII 笔误需改正）；事件 record 字段名（messageId/toolCallId/delta/threadId/runId）跨 Task 一致；`SessionStore.get/put`、`SdkResultMessage.subtype().wire()`、`ContentBlock.ToolResult.toolUseId()/content()` 均与核心库实读签名一致。

**已知需在实现时坐实的点**（已在文中标注，非阻塞）：①AG-UI 事件字段精确名；②Options builder 环境剥离方法；③SDK result 后是否触发 onComplete；④message.content 是否结构化。
