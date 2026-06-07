package com.anthropic.claude.agent.agui;

import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.anthropic.claude.agent.message.ApiMessage;
import com.anthropic.claude.agent.message.ContentBlock;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkPartialAssistantMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.message.SdkUserMessage;
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
        } else if (msg instanceof SdkUserMessage u) {
            handleUser(u);
        } else if (msg instanceof SdkResultMessage r) {
            handleResult(r);
        }
    }

    @Override
    public void onError(Throwable t) {
        sink.accept(new AgUiEvent.RunError(String.valueOf(t.getMessage()), "exception"));
    }

    @Override
    public void onComplete() {
        // 终结由 SdkResultMessage 驱动（handleResult）；此处无操作
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
        } else if ("tool_use".equals(blockType)) {
            String id = text(block, "id");
            openBlocks.put(index, new OpenBlock(Kind.TOOL, id));
            sink.accept(new AgUiEvent.ToolCallStart(id, text(block, "name")));
        } else if ("thinking".equals(blockType) || "redacted_thinking".equals(blockType)) {
            String id = "reason-" + msgSeq.incrementAndGet();
            openBlocks.put(index, new OpenBlock(Kind.THINKING, id));
            sink.accept(new AgUiEvent.ReasoningStart(id));
            sink.accept(new AgUiEvent.ReasoningMessageStart(id));
        }
    }

    private void onBlockDelta(int index, JsonNode delta) {
        OpenBlock open = openBlocks.get(index);
        if (open == null) {
            return;
        }
        String deltaType = text(delta, "type");
        if (open.kind() == Kind.TEXT && "text_delta".equals(deltaType)) {
            sink.accept(new AgUiEvent.TextMessageContent(open.id(), text(delta, "text")));
        } else if (open.kind() == Kind.TOOL && "input_json_delta".equals(deltaType)) {
            sink.accept(new AgUiEvent.ToolCallArgs(open.id(), text(delta, "partial_json")));
        } else if (open.kind() == Kind.THINKING && "thinking_delta".equals(deltaType)) {
            sink.accept(new AgUiEvent.ReasoningMessageContent(open.id(), text(delta, "thinking")));
        }
        // signature_delta 不产出事件（加密签名，无明文）
    }

    private void onBlockStop(int index) {
        OpenBlock open = openBlocks.remove(index);
        if (open == null) {
            return;
        }
        if (open.kind() == Kind.TEXT) {
            sink.accept(new AgUiEvent.TextMessageEnd(open.id()));
        } else if (open.kind() == Kind.TOOL) {
            sink.accept(new AgUiEvent.ToolCallEnd(open.id()));
        } else if (open.kind() == Kind.THINKING) {
            sink.accept(new AgUiEvent.ReasoningMessageEnd(open.id()));
            sink.accept(new AgUiEvent.ReasoningEnd(open.id()));
        }
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

    private void handleResult(SdkResultMessage r) {
        if (r.sessionId() != null) {
            sessionStore.put(threadId, r.sessionId());
        }
        if (r.isError() || r.subtype() != SdkResultMessage.Subtype.SUCCESS) {
            String wire = r.subtype() == null ? null : r.subtype().wire();
            String code = wire == null ? "error" : wire;
            sink.accept(new AgUiEvent.RunError("claude run error: " + code, code));
        } else {
            sink.accept(new AgUiEvent.RunFinished(threadId, runId));
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
