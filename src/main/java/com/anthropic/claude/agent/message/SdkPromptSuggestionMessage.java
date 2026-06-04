package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "prompt_suggestion"} — a predicted next user prompt (only emitted with
 * {@code promptSuggestions} enabled).
 *
 * @param suggestion the suggested next prompt
 * @param sessionId  session id
 * @param uuid       message uuid
 * @param raw        original JSON
 */
public record SdkPromptSuggestionMessage(
        String suggestion,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "prompt_suggestion";
    }
}
