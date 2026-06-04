package com.anthropic.claude.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An in-process MCP tool definition (the Java analogue of the Node SDK's {@code tool()}).
 *
 * @param name        tool name (as the model will call it)
 * @param description human-readable description
 * @param inputSchema JSON Schema for the tool input (may be null → defaults to {@code {type:object}})
 * @param handler     the implementation
 */
public record SdkMcpTool(String name, String description, JsonNode inputSchema, ToolHandler handler) {

    public static SdkMcpTool of(String name, String description, JsonNode inputSchema, ToolHandler handler) {
        return new SdkMcpTool(name, description, inputSchema, handler);
    }
}
