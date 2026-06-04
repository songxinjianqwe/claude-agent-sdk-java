package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One message in the stream emitted by {@code claude} (the Java analogue of the Node SDK's
 * {@code SDKMessage} union). Discriminated on the wire {@code type} field.
 *
 * <p>Parse with {@link SdkMessages#parse(String)}. Unknown {@code type} values yield
 * {@link SdkUnknownMessage} rather than throwing, so a newer CLI never breaks an older SDK.
 *
 * <p>Use Java pattern matching to consume:
 * <pre>{@code
 * switch (msg) {
 *     case SdkAssistantMessage a -> System.out.println(a.message().text());
 *     case SdkResultMessage r when r.isError() -> ...;
 *     case SdkResultMessage r -> ...;
 *     default -> {}
 * }
 * }</pre>
 */
public sealed interface SdkMessage
        permits SdkAssistantMessage, SdkUserMessage, SdkResultMessage, SdkSystemMessage,
                SdkPartialAssistantMessage, SdkRateLimitMessage, SdkToolProgressMessage,
                SdkToolUseSummaryMessage, SdkAuthStatusMessage, SdkPromptSuggestionMessage,
                SdkUnknownMessage {

    /** The wire {@code type} discriminator (e.g. "assistant", "result", "system", "stream_event"). */
    String type();

    /**
     * The original JSON node as received — escape hatch for fields not modeled explicitly and the
     * basis of drift-tolerance.
     */
    JsonNode raw();

    /** The session id ({@code session_id}), or null if absent. */
    default String sessionId() {
        return text(raw(), "session_id");
    }

    /** The message uuid, or null if absent. */
    default String uuid() {
        return text(raw(), "uuid");
    }

    /** Read a nullable string field from a node (null if missing or JSON null). */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
