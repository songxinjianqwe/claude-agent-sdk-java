package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses stream-json NDJSON lines into the {@link SdkMessage} sealed tree.
 *
 * <p>Dispatch is manual (on the wire {@code type}, then {@code subtype} for {@code result}) rather
 * than via Jackson polymorphic annotations, so that: (a) unknown {@code type}s fall back to
 * {@link SdkUnknownMessage} instead of throwing, (b) every message keeps its raw {@link JsonNode},
 * and (c) lenient parsing tolerates added fields ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}).
 */
public final class SdkMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SdkMessages() {
    }

    /** A shared, lenient {@link ObjectMapper} used across the SDK. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Parse one NDJSON line into an {@link SdkMessage}.
     *
     * @throws SdkMessageParseException if the line is not valid JSON
     */
    public static SdkMessage parse(String line) {
        if (line == null || line.isBlank()) {
            throw new SdkMessageParseException("blank stream-json line", line, null);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new SdkMessageParseException("invalid JSON in stream-json line", line, e);
        }
        if (node == null || !node.isObject()) {
            throw new SdkMessageParseException("stream-json line is not a JSON object", line, null);
        }
        return fromJson(node);
    }

    /** Build an {@link SdkMessage} from an already-parsed JSON object node. */
    public static SdkMessage fromJson(JsonNode node) {
        String type = SdkMessage.text(node, "type");
        if (type == null) {
            return new SdkUnknownMessage(null, node);
        }
        return switch (type) {
            case "assistant" -> new SdkAssistantMessage(
                    SdkMessage.text(node, "uuid"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "parent_tool_use_id"),
                    SdkMessage.text(node, "request_id"),
                    parseApiMessage(node.get("message")),
                    node);
            case "user" -> new SdkUserMessage(
                    SdkMessage.text(node, "uuid"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "parent_tool_use_id"),
                    parseApiMessage(node.get("message")),
                    node);
            case "result" -> new SdkResultMessage(
                    SdkResultMessage.Subtype.fromWire(SdkMessage.text(node, "subtype")),
                    boolOrFalse(node, "is_error"),
                    SdkMessage.text(node, "result"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    intOrNull(node, "num_turns"),
                    longOrNull(node, "duration_ms"),
                    longOrNull(node, "duration_api_ms"),
                    doubleOrNull(node, "total_cost_usd"),
                    SdkMessage.text(node, "stop_reason"),
                    node.get("usage"),
                    node.get("modelUsage"),
                    node);
            case "system" -> new SdkSystemMessage(
                    SdkMessage.text(node, "subtype"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            case "stream_event" -> new SdkPartialAssistantMessage(
                    node.get("event"),
                    SdkMessage.text(node, "parent_tool_use_id"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            case "rate_limit_event" -> new SdkRateLimitMessage(
                    node.get("rate_limit_info"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            case "tool_progress" -> new SdkToolProgressMessage(
                    SdkMessage.text(node, "tool_use_id"),
                    SdkMessage.text(node, "tool_name"),
                    SdkMessage.text(node, "parent_tool_use_id"),
                    doubleOrNull(node, "elapsed_time_seconds"),
                    SdkMessage.text(node, "task_id"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            case "tool_use_summary" -> new SdkToolUseSummaryMessage(
                    SdkMessage.text(node, "summary"),
                    stringList(node.get("preceding_tool_use_ids")),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            case "auth_status" -> new SdkAuthStatusMessage(
                    boolOrFalse(node, "isAuthenticating"),
                    stringList(node.get("output")),
                    SdkMessage.text(node, "error"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            case "prompt_suggestion" -> new SdkPromptSuggestionMessage(
                    SdkMessage.text(node, "suggestion"),
                    SdkMessage.text(node, "session_id"),
                    SdkMessage.text(node, "uuid"),
                    node);
            default -> new SdkUnknownMessage(type, node);
        };
    }

    // --- inner Anthropic message + content blocks ------------------------------------------------

    private static ApiMessage parseApiMessage(JsonNode m) {
        if (m == null || !m.isObject()) {
            return new ApiMessage(null, null, null, List.of(), null, null, m);
        }
        return new ApiMessage(
                SdkMessage.text(m, "role"),
                SdkMessage.text(m, "model"),
                SdkMessage.text(m, "id"),
                parseContent(m.get("content")),
                SdkMessage.text(m, "stop_reason"),
                m.get("usage"),
                m);
    }

    private static List<ContentBlock> parseContent(JsonNode content) {
        List<ContentBlock> out = new ArrayList<>();
        if (content == null || content.isNull()) {
            return out;
        }
        if (content.isTextual()) {
            // Anthropic allows `content` to be a bare string (common for user input).
            out.add(new ContentBlock.Text(content.asText(), content));
            return out;
        }
        if (content.isArray()) {
            for (JsonNode b : content) {
                out.add(parseBlock(b));
            }
        }
        return out;
    }

    private static ContentBlock parseBlock(JsonNode b) {
        String type = SdkMessage.text(b, "type");
        if (type == null) {
            return new ContentBlock.Unknown(null, b);
        }
        return switch (type) {
            case "text" -> new ContentBlock.Text(SdkMessage.text(b, "text"), b);
            case "thinking" -> new ContentBlock.Thinking(
                    SdkMessage.text(b, "thinking"), SdkMessage.text(b, "signature"), b);
            case "redacted_thinking" -> new ContentBlock.RedactedThinking(
                    SdkMessage.text(b, "data"), b);
            case "tool_use" -> new ContentBlock.ToolUse(
                    SdkMessage.text(b, "id"), SdkMessage.text(b, "name"), b.get("input"), b);
            case "tool_result" -> new ContentBlock.ToolResult(
                    SdkMessage.text(b, "tool_use_id"), b.get("content"), boolOrNull(b, "is_error"), b);
            case "image" -> new ContentBlock.Image(b.get("source"), b);
            default -> new ContentBlock.Unknown(type, b);
        };
    }

    // --- nullable numeric/boolean readers --------------------------------------------------------

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || !v.isNumber()) ? null : v.intValue();
    }

    private static Long longOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || !v.isNumber()) ? null : v.longValue();
    }

    private static Double doubleOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || !v.isNumber()) ? null : v.doubleValue();
    }

    private static Boolean boolOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || !v.isBoolean()) ? null : v.booleanValue();
    }

    private static boolean boolOrFalse(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v != null && v.isBoolean() && v.booleanValue();
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode e : arr) {
                out.add(e.asText());
            }
        }
        return out;
    }
}
