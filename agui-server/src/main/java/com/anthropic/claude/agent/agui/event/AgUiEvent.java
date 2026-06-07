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
