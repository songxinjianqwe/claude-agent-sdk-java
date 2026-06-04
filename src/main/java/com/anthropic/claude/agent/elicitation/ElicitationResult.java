package com.anthropic.claude.agent.elicitation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The host's response to an {@link ElicitationRequest} ({@code {action, content?}}). When a callback
 * errors or times out, the SDK defaults to {@link #cancel()}.
 *
 * @param action  "accept", "decline", or "cancel"
 * @param content the provided input (for "accept" in form mode), nullable
 */
public record ElicitationResult(String action, JsonNode content) {

    /** Accept with the provided form content. */
    public static ElicitationResult accept(JsonNode content) {
        return new ElicitationResult("accept", content);
    }

    /** Accept with no content. */
    public static ElicitationResult accept() {
        return new ElicitationResult("accept", null);
    }

    public static ElicitationResult decline() {
        return new ElicitationResult("decline", null);
    }

    public static ElicitationResult cancel() {
        return new ElicitationResult("cancel", null);
    }
}
