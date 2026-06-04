package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single content block inside an {@link ApiMessage} (mirrors Anthropic Messages API content
 * blocks: {@code text}, {@code thinking}, {@code redacted_thinking}, {@code tool_use},
 * {@code tool_result}). Unknown block types fall back to {@link Unknown} so protocol drift never
 * breaks parsing.
 *
 * <p>Every variant keeps the original {@link JsonNode} ({@link #raw()}) as an escape hatch.
 */
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Thinking, ContentBlock.RedactedThinking,
                ContentBlock.ToolUse, ContentBlock.ToolResult, ContentBlock.Image,
                ContentBlock.Unknown {

    /** The wire {@code type} of this block. */
    String type();

    /** The raw JSON of this block. */
    JsonNode raw();

    /** {@code {"type":"text","text": ...}} */
    record Text(String text, JsonNode raw) implements ContentBlock {
        @Override
        public String type() {
            return "text";
        }
    }

    /**
     * {@code {"type":"thinking","thinking": ...,"signature": ...}}.
     * Note: with the current CLI, extended thinking is typically redacted — {@code thinking} text
     * may be present but {@code signature} is the encrypted form (see project devdocs on
     * thinking-content-redacted).
     */
    record Thinking(String thinking, String signature, JsonNode raw) implements ContentBlock {
        @Override
        public String type() {
            return "thinking";
        }
    }

    /** {@code {"type":"redacted_thinking","data": ...}} */
    record RedactedThinking(String data, JsonNode raw) implements ContentBlock {
        @Override
        public String type() {
            return "redacted_thinking";
        }
    }

    /** {@code {"type":"tool_use","id": ...,"name": ...,"input": {...}}} */
    record ToolUse(String id, String name, JsonNode input, JsonNode raw) implements ContentBlock {
        @Override
        public String type() {
            return "tool_use";
        }
    }

    /**
     * {@code {"type":"tool_result","tool_use_id": ...,"content": ...,"is_error": bool}}.
     * {@code content} is kept as a raw node because it may be a string or an array of blocks.
     */
    record ToolResult(String toolUseId, JsonNode content, Boolean isError, JsonNode raw)
            implements ContentBlock {
        @Override
        public String type() {
            return "tool_result";
        }
    }

    /**
     * {@code {"type":"image","source": {...}}}. {@code source} is kept raw (base64 vs url variants).
     */
    record Image(JsonNode source, JsonNode raw) implements ContentBlock {
        @Override
        public String type() {
            return "image";
        }
    }

    /** Any block whose {@code type} we do not model explicitly. */
    record Unknown(String type, JsonNode raw) implements ContentBlock {
    }
}
