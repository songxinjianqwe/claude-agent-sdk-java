package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "user"} — a user-turn message. Emitted both for input echoes and for tool_result
 * turns (the harness feeding tool outputs back to the model), and during {@code resume} replay.
 * Mirrors the Node SDK {@code SDKUserMessage} / {@code SDKUserMessageReplay}.
 *
 * @param uuid            uuid (may be null on replayed messages)
 * @param sessionId       the session id
 * @param parentToolUseId set when nested under a tool (subagent), else null
 * @param message         the inner Anthropic Messages API object (content may be tool_result blocks)
 * @param raw             original JSON
 */
public record SdkUserMessage(
        String uuid,
        String sessionId,
        String parentToolUseId,
        ApiMessage message,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "user";
    }
}
