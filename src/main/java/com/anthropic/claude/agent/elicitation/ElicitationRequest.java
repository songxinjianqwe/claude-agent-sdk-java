package com.anthropic.claude.agent.elicitation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An elicitation request forwarded from an MCP server (the CLI→SDK {@code elicitation} control
 * request). The host should prompt the user and return an {@link ElicitationResult}.
 *
 * @param serverName      the MCP server requesting input ({@code mcp_server_name})
 * @param message         message to display
 * @param mode            "form" (structured input) or "url" (browser auth), nullable
 * @param url             URL to open (url mode), nullable
 * @param elicitationId   id correlating url elicitations with completion ({@code elicitation_id})
 * @param requestedSchema JSON Schema for the requested input (form mode), nullable
 */
public record ElicitationRequest(
        String serverName,
        String message,
        String mode,
        String url,
        String elicitationId,
        JsonNode requestedSchema) {
}
