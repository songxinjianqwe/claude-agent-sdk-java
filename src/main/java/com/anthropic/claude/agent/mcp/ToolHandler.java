package com.anthropic.claude.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** Handler for an in-process MCP tool call. Runs on a background thread; may block. */
@FunctionalInterface
public interface ToolHandler {

    /**
     * @param arguments the tool's {@code arguments} object from the MCP {@code tools/call} request
     * @return the tool result (use {@link McpToolResult#text}/{@link McpToolResult#error})
     */
    McpToolResult call(JsonNode arguments);
}
