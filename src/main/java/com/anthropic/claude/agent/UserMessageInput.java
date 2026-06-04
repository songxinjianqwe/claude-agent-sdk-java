package com.anthropic.claude.agent;

import com.anthropic.claude.agent.message.SdkMessages;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A structured user-turn input (the input side of the Node SDK's {@code SDKUserMessage}). The
 * {@code content} is the Anthropic {@code MessageParam.content} — either a plain string or an array
 * of content blocks (text / image / tool_result) — with an optional {@code parent_tool_use_id}.
 *
 * <p>Serialized to {@code {"type":"user","message":{"role":"user","content": <content>},
 * "parent_tool_use_id"?: ...}}.
 *
 * @param content          a JSON string or content-block array
 * @param parentToolUseId  parent tool id when nested under a tool (nullable)
 */
public record UserMessageInput(JsonNode content, String parentToolUseId) {

    /** A plain-text user turn. */
    public static UserMessageInput text(String text) {
        return new UserMessageInput(SdkMessages.mapper().getNodeFactory().textNode(text), null);
    }

    /** A user turn with an explicit content-block array (text / image / tool_result blocks). */
    public static UserMessageInput blocks(JsonNode contentArray) {
        return new UserMessageInput(contentArray, null);
    }

    public UserMessageInput withParentToolUseId(String parentToolUseId) {
        return new UserMessageInput(content, parentToolUseId);
    }
}
