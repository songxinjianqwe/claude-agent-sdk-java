package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "stream_event"} — an incremental streaming event (raw Anthropic
 * {@code RawMessageStreamEvent}: message_start / content_block_start / content_block_delta /
 * message_delta / message_stop). Only emitted when the SDK starts the CLI with
 * {@code --include-partial-messages} (Options.includePartialMessages). Mirrors the Node SDK
 * {@code SDKPartialAssistantMessage}.
 *
 * @param event           the raw stream event (kept as JSON; shape varies by event type)
 * @param parentToolUseId set when nested under a tool, else null
 * @param sessionId       session id
 * @param uuid            message uuid
 * @param raw             original JSON
 */
public record SdkPartialAssistantMessage(
        JsonNode event,
        String parentToolUseId,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "stream_event";
    }

    /** The inner stream event's {@code type} (e.g. "content_block_delta"), or null. */
    public String eventType() {
        return SdkMessage.text(event, "type");
    }
}
