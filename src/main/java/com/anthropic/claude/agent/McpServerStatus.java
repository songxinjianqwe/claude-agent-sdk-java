package com.anthropic.claude.agent;

import com.anthropic.claude.agent.message.SdkMessage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Status of one MCP server, as returned by the {@code mcp_status} control request.
 *
 * @param name   server name
 * @param status connected / failed / needs-auth / pending / disabled
 * @param raw    full status node (serverInfo, error, config, scope, tools, capabilities, …)
 */
public record McpServerStatus(String name, String status, JsonNode raw) {

    /** Parse one MCP server status node. */
    public static McpServerStatus from(JsonNode n) {
        return new McpServerStatus(SdkMessage.text(n, "name"), SdkMessage.text(n, "status"), n);
    }
}
