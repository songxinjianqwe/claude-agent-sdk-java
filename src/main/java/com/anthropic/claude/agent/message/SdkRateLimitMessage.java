package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "rate_limit_event"} — periodic rate-limit status pushed by the CLI
 * ({@code rate_limit_info}: status / resetsAt / rateLimitType / overageStatus / ...).
 *
 * @param rateLimitInfo raw {@code rate_limit_info} node
 * @param sessionId     session id
 * @param uuid          message uuid
 * @param raw           original JSON
 */
public record SdkRateLimitMessage(
        JsonNode rateLimitInfo,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "rate_limit_event";
    }
}
