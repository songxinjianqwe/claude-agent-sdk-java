package com.anthropic.claude.agent.mcp;

import java.util.List;
import java.util.Map;

/**
 * A type-safe external MCP server config (the serializable members of the Node SDK
 * {@code McpServerConfig} union: stdio / sse / http). In-process ({@code type:'sdk'}) servers are
 * configured separately via {@link com.anthropic.claude.agent.Options.Builder#mcpServer(SdkMcpServer)}.
 *
 * <p>Registered via {@code Options.builder().mcpServer(name, config)} and serialized into the
 * {@code --mcp-config} JSON ({@code {"mcpServers": {name: {...}}}}).
 */
public sealed interface McpServerConfig
        permits McpServerConfig.Stdio, McpServerConfig.Sse, McpServerConfig.Http {

    /** {@code {type:"stdio", command, args?, env?, timeout?}} */
    record Stdio(String command, List<String> args, Map<String, String> env, Integer timeout)
            implements McpServerConfig {
    }

    /** {@code {type:"sse", url, headers?, timeout?, alwaysLoad?}} */
    record Sse(String url, Map<String, String> headers, Integer timeout, Boolean alwaysLoad)
            implements McpServerConfig {
    }

    /** {@code {type:"http", url, headers?, timeout?, alwaysLoad?}} */
    record Http(String url, Map<String, String> headers, Integer timeout, Boolean alwaysLoad)
            implements McpServerConfig {
    }

    static Stdio stdio(String command, String... args) {
        return new Stdio(command, List.of(args), null, null);
    }

    static Sse sse(String url) {
        return new Sse(url, null, null, null);
    }

    static Http http(String url) {
        return new Http(url, null, null, null);
    }
}
