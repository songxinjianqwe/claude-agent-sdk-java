package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fallback for any top-level {@code type} this SDK version does not model (e.g. a newer CLI's
 * {@code tool_progress} / {@code auth_status} / {@code prompt_suggestion}). Carrying the raw JSON
 * instead of throwing keeps an older SDK working against a newer CLI (protocol-drift tolerance).
 *
 * @param type the unrecognized wire {@code type} (null if the message had no {@code type} field)
 * @param raw  original JSON
 */
public record SdkUnknownMessage(String type, JsonNode raw) implements SdkMessage {
}
