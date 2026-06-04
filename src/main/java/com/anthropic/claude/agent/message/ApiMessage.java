package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The inner Anthropic Messages API object carried by {@code assistant} / {@code user} SDK messages
 * (the {@code "message"} field). Only the commonly-used fields are modeled; {@link #raw()} exposes
 * the rest (e.g. {@code stop_sequence}, {@code stop_details}, {@code diagnostics},
 * {@code context_management}, full {@code usage}).
 *
 * @param role       "assistant" or "user"
 * @param model      model id (present on assistant messages; null on user messages)
 * @param id         message id (e.g. "msg_..."; null on user messages)
 * @param content    parsed content blocks (a string {@code content} is normalized to one
 *                   {@link ContentBlock.Text})
 * @param stopReason e.g. "end_turn", "tool_use", null while streaming
 * @param usage      raw {@code usage} node (token counts), may be null
 * @param raw        the original {@code message} JSON node
 */
public record ApiMessage(
        String role,
        String model,
        String id,
        List<ContentBlock> content,
        String stopReason,
        JsonNode usage,
        JsonNode raw) {

    /** Concatenate all {@link ContentBlock.Text} blocks (convenience for the common text case). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : content) {
            if (b instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
