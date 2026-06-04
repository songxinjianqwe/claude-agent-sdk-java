package com.anthropic.claude.agent.internal;

import com.anthropic.claude.agent.mcp.McpToolResult;
import com.anthropic.claude.agent.mcp.SdkMcpServer;
import com.anthropic.claude.agent.mcp.SdkMcpTool;
import com.anthropic.claude.agent.message.SdkMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * A minimal MCP server hosted in-process, speaking JSON-RPC 2.0 over the control channel's
 * {@code mcp_message} transport. Handles the subset the CLI's MCP client uses: {@code initialize},
 * {@code notifications/initialized}, {@code ping}, {@code tools/list}, {@code tools/call}.
 *
 * <p>{@link #handle} returns the JSON-RPC response node, or {@code null} for notifications (which
 * have no id and warrant no reply).
 */
public final class InProcessMcpServer {

    private static final Logger LOG = System.getLogger(InProcessMcpServer.class.getName());
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    private final SdkMcpServer server;
    private final ObjectMapper mapper;

    public InProcessMcpServer(SdkMcpServer server, ObjectMapper mapper) {
        this.server = server;
        this.mapper = mapper;
    }

    public String name() {
        return server.name();
    }

    /** Handle one JSON-RPC message; returns the response node, or null for notifications. */
    public JsonNode handle(JsonNode message) {
        if (message == null || !message.isObject()) {
            return null;
        }
        String method = SdkMessage.text(message, "method");
        if (method == null) {
            return null; // a response/invalid frame — nothing to do
        }
        JsonNode id = message.get("id");
        boolean isNotification = id == null || id.isNull();
        try {
            return switch (method) {
                case "initialize" -> success(id, initializeResult(message));
                case "ping" -> success(id, mapper.createObjectNode());
                case "tools/list" -> success(id, toolsListResult());
                case "tools/call" -> success(id, toolsCallResult(message.get("params")));
                case "notifications/initialized", "notifications/cancelled" -> null;
                default -> isNotification ? null : error(id, -32601, "Method not found: " + method);
            };
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "in-process MCP handler error for method " + method, e);
            return isNotification ? null : error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private ObjectNode initializeResult(JsonNode message) {
        ObjectNode r = mapper.createObjectNode();
        String pv = message.has("params") ? SdkMessage.text(message.get("params"), "protocolVersion") : null;
        r.put("protocolVersion", pv != null ? pv : DEFAULT_PROTOCOL_VERSION);
        r.putObject("capabilities").putObject("tools"); // advertise tools capability
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", server.name());
        info.put("version", server.version());
        return r;
    }

    private ObjectNode toolsListResult() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode tools = r.putArray("tools");
        for (SdkMcpTool tool : server.tools()) {
            ObjectNode tn = tools.addObject();
            tn.put("name", tool.name());
            if (tool.description() != null) {
                tn.put("description", tool.description());
            }
            tn.set("inputSchema", tool.inputSchema() != null
                    ? tool.inputSchema()
                    : mapper.createObjectNode().put("type", "object"));
        }
        return r;
    }

    private ObjectNode toolsCallResult(JsonNode params) {
        String toolName = SdkMessage.text(params, "name");
        JsonNode arguments = params == null ? null : params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = mapper.createObjectNode();
        }
        SdkMcpTool tool = findTool(toolName);
        McpToolResult result;
        if (tool == null) {
            result = McpToolResult.error("Unknown tool: " + toolName);
        } else {
            try {
                result = tool.handler().call(arguments);
                if (result == null) {
                    result = McpToolResult.error("tool returned null");
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "MCP tool '" + toolName + "' threw", e);
                result = McpToolResult.error("tool execution failed: " + e.getMessage());
            }
        }
        ObjectNode r = mapper.createObjectNode();
        r.set("content", result.content());
        r.put("isError", result.isError());
        return r;
    }

    private SdkMcpTool findTool(String name) {
        if (name == null) {
            return null;
        }
        for (SdkMcpTool tool : server.tools()) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode err = r.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return r;
    }
}
