package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * {@code type: "tool_use_summary"} — a summary of one or more preceding tool uses.
 *
 * @param summary               the summary text
 * @param precedingToolUseIds   the tool_use ids this summarizes
 * @param sessionId             session id
 * @param uuid                  message uuid
 * @param raw                   original JSON
 */
public record SdkToolUseSummaryMessage(
        String summary,
        List<String> precedingToolUseIds,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "tool_use_summary";
    }
}
