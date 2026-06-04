package com.anthropic.claude.agent.mcp;

import java.util.List;

/**
 * An in-process MCP server hosted inside the JVM (the Java analogue of the Node SDK's
 * {@code createSdkMcpServer()}). Its tools are exposed to {@code claude} over the control channel's
 * {@code mcp_message} JSON-RPC transport — no separate process or socket.
 *
 * @param name    server name (referenced by the model as {@code mcp__<name>__<tool>})
 * @param version server version reported in the MCP handshake
 * @param tools   the tools this server provides
 */
public record SdkMcpServer(String name, String version, List<SdkMcpTool> tools) {

    public SdkMcpServer {
        tools = List.copyOf(tools);
    }

    public static SdkMcpServer create(String name, String version, List<SdkMcpTool> tools) {
        return new SdkMcpServer(name, version, tools);
    }

    public static SdkMcpServer create(String name, SdkMcpTool... tools) {
        return new SdkMcpServer(name, "1.0.0", List.of(tools));
    }
}
