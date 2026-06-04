package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.mcp.McpToolResult;
import com.anthropic.claude.agent.testkit.FakeTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Phase 7: in-process MCP end-to-end through Query — declared in initialize, dispatched via mcp_message. */
public class McpRoundTripTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitWritten(FakeTransport t, int n) {
        long deadline = System.currentTimeMillis() + 2000;
        while (t.written.size() < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + n + " writes, had " + t.written.size());
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void serverDeclaredInInitializeAndToolCallDispatched() {
        FakeTransport t = new FakeTransport();
        AtomicBoolean handlerRan = new AtomicBoolean(false);

        Options opts = Options.builder()
                .mcpServer(ClaudeAgent.createSdkMcpServer("calc",
                        ClaudeAgent.tool("add", "add", null, args -> {
                            handlerRan.set(true);
                            return McpToolResult.text("sum=" + (args.get("a").asInt() + args.get("b").asInt()));
                        })))
                .build();
        ClaudeAgent.streamingQuery(opts, t);

        // initialize declares the in-process server by name
        JsonNode init = parse(t.written.get(0));
        assertEquals("initialize", init.get("request").get("subtype").asText());
        JsonNode servers = init.get("request").get("sdkMcpServers");
        assertEquals(1, servers.size());
        assertEquals("calc", servers.get(0).asText());

        // CLI calls the tool over mcp_message
        t.feedLine("{\"type\":\"control_request\",\"request_id\":\"m1\",\"request\":"
                + "{\"subtype\":\"mcp_message\",\"server_name\":\"calc\",\"message\":"
                + "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"add\",\"arguments\":{\"a\":6,\"b\":7}}}}}");

        awaitWritten(t, 2);
        JsonNode resp = parse(t.written.get(t.written.size() - 1));
        assertEquals("control_response", resp.get("type").asText());
        assertEquals("m1", resp.get("response").get("request_id").asText());
        JsonNode rpc = resp.get("response").get("response").get("mcp_response");
        assertEquals(7, rpc.get("id").asInt());
        assertEquals("sum=13", rpc.get("result").get("content").get(0).get("text").asText());
        assertTrue(handlerRan.get());
    }

    @Test
    public void unknownMcpServerRepliesError() {
        FakeTransport t = new FakeTransport();
        Options opts = Options.builder()
                .mcpServer(ClaudeAgent.createSdkMcpServer("calc",
                        ClaudeAgent.tool("noop", "noop", null, args -> McpToolResult.text("ok"))))
                .build();
        ClaudeAgent.streamingQuery(opts, t);
        int before = t.written.size();

        t.feedLine("{\"type\":\"control_request\",\"request_id\":\"m2\",\"request\":"
                + "{\"subtype\":\"mcp_message\",\"server_name\":\"ghost\",\"message\":"
                + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}}}");

        awaitWritten(t, before + 1);
        JsonNode resp = parse(t.written.get(t.written.size() - 1));
        assertEquals("error", resp.get("response").get("subtype").asText());
        assertTrue(resp.get("response").get("error").asText().contains("ghost"));
    }
}
