package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "assistant"} — a complete assistant turn (the model's response, possibly containing
 * text / thinking / tool_use blocks). Mirrors the Node SDK {@code SDKAssistantMessage}.
 *
 * @param uuid            this message's uuid
 * @param sessionId       the session id
 * @param parentToolUseId set when this assistant message is nested under a tool (subagent), else null
 * @param requestId       the Anthropic API request id (e.g. "req_...")
 * @param message         the inner Anthropic Messages API object
 * @param raw             original JSON
 */
public record SdkAssistantMessage(
        String uuid,
        String sessionId,
        String parentToolUseId,
        String requestId,
        ApiMessage message,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "assistant";
    }
}
