package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "tool_progress"} — periodic progress for a long-running tool call.
 *
 * @param toolUseId           the tool_use id
 * @param toolName            the tool name
 * @param parentToolUseId     parent tool id (subagent), may be null
 * @param elapsedTimeSeconds  elapsed time, may be null
 * @param taskId              associated task id, may be null
 * @param sessionId           session id
 * @param uuid                message uuid
 * @param raw                 original JSON
 */
public record SdkToolProgressMessage(
        String toolUseId,
        String toolName,
        String parentToolUseId,
        Double elapsedTimeSeconds,
        String taskId,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "tool_progress";
    }
}
