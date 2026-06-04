package com.anthropic.claude.agent;

import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.mcp.McpToolResult;
import com.anthropic.claude.agent.message.SdkMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

/**
 * Real end-to-end validation of the in-process MCP round trip (initialize handshake +
 * mcp_message JSON-RPC) against the installed {@code claude}. Skipped unless
 * {@code -Dclaude.sdk.it=true}.
 *
 * <pre>{@code mvn test -Dtest=RealMcpSmokeTest -Dclaude.sdk.it=true}</pre>
 */
public class RealMcpSmokeTest {

    @Test
    public void realClaudeCallsInProcessMcpTool() throws Exception {
        Assume.assumeTrue("set -Dclaude.sdk.it=true", Boolean.getBoolean("claude.sdk.it"));

        ObjectMapper mapper = new ObjectMapper();
        Path cwd = Files.createTempDirectory("claude-mcp-it");
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        AtomicReference<Integer> computed = new AtomicReference<>();

        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":"
                + "{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"]}");
        var server = ClaudeAgent.createSdkMcpServer("calc",
                ClaudeAgent.tool("add", "Add two integers a and b", schema, args -> {
                    handlerRan.set(true);
                    int s = args.get("a").asInt() + args.get("b").asInt();
                    computed.set(s);
                    return McpToolResult.text(String.valueOf(s));
                }));

        Options opts = Options.builder()
                .cwd(cwd.toString())
                .model("haiku")
                .mcpServer(server)
                .allowedTools("mcp__calc__add")
                .build();

        Query q = ClaudeAgent.query(
                "Use the add tool to compute 17 + 25. Reply with only the resulting number.", opts);
        List<SdkMessage> msgs = q.collect();

        assertTrue("real claude should have invoked the in-process MCP tool", handlerRan.get());
        assertTrue("tool computed 17+25=42", Integer.valueOf(42).equals(computed.get()));
        assertTrue("got some messages", !msgs.isEmpty());
    }
}
