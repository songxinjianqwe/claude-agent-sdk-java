package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * {@code type: "auth_status"} — authentication progress (only emitted with {@code --enable-auth-status}).
 *
 * @param authenticating whether authentication is in progress
 * @param output         output lines
 * @param error          error message, may be null
 * @param sessionId      session id
 * @param uuid           message uuid
 * @param raw            original JSON
 */
public record SdkAuthStatusMessage(
        boolean authenticating,
        List<String> output,
        String error,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "auth_status";
    }
}
