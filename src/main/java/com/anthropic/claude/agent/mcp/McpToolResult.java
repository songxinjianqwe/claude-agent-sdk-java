package com.anthropic.claude.agent.mcp;

import com.anthropic.claude.agent.message.SdkMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * The result of an in-process MCP {@link ToolHandler}. Carries the MCP {@code content} array (e.g.
 * a list of {@code {type:"text",text:...}} blocks) and an {@code isError} flag.
 */
public record McpToolResult(JsonNode content, boolean isError) {

    /** A successful text result. */
    public static McpToolResult text(String text) {
        return new McpToolResult(textContent(text), false);
    }

    /** An error text result ({@code isError:true}). */
    public static McpToolResult error(String text) {
        return new McpToolResult(textContent(text), true);
    }

    /** A result with an explicit MCP content array. */
    public static McpToolResult of(JsonNode contentArray, boolean isError) {
        return new McpToolResult(contentArray, isError);
    }

    private static ArrayNode textContent(String text) {
        ArrayNode arr = SdkMessages.mapper().createArrayNode();
        arr.addObject().put("type", "text").put("text", text);
        return arr;
    }
}
