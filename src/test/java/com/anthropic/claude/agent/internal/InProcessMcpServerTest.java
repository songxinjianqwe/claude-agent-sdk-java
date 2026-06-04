package com.anthropic.claude.agent.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.mcp.McpToolResult;
import com.anthropic.claude.agent.mcp.SdkMcpServer;
import com.anthropic.claude.agent.mcp.SdkMcpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/** Phase 7: the in-process MCP JSON-RPC server handles the methods the CLI's MCP client uses. */
public class InProcessMcpServerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private InProcessMcpServer server() {
        JsonNode schema = parse("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},"
                + "\"b\":{\"type\":\"number\"}},\"required\":[\"a\",\"b\"]}");
        SdkMcpTool add = SdkMcpTool.of("add", "add two numbers", schema,
                args -> McpToolResult.text("sum=" + (args.get("a").asInt() + args.get("b").asInt())));
        return new InProcessMcpServer(SdkMcpServer.create("calc", add), M);
    }

    @Test
    public void initializeReportsServerInfoAndEchoesProtocolVersion() {
        JsonNode r = server().handle(parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}"));
        assertEquals("2.0", r.get("jsonrpc").asText());
        assertEquals(1, r.get("id").asInt());
        JsonNode result = r.get("result");
        assertEquals("2025-06-18", result.get("protocolVersion").asText());
        assertEquals("calc", result.get("serverInfo").get("name").asText());
        assertTrue(result.get("capabilities").has("tools"));
    }

    @Test
    public void toolsListReturnsToolWithSchema() {
        JsonNode r = server().handle(parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        JsonNode tools = r.get("result").get("tools");
        assertEquals(1, tools.size());
        assertEquals("add", tools.get(0).get("name").asText());
        assertEquals("add two numbers", tools.get(0).get("description").asText());
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asText());
    }

    @Test
    public void toolsCallInvokesHandler() {
        JsonNode r = server().handle(parse("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"add\",\"arguments\":{\"a\":4,\"b\":5}}}"));
        JsonNode result = r.get("result");
        assertEquals(false, result.get("isError").asBoolean());
        assertEquals("sum=9", result.get("content").get(0).get("text").asText());
        assertEquals("text", result.get("content").get(0).get("type").asText());
    }

    @Test
    public void toolsCallUnknownToolReturnsErrorResult() {
        JsonNode r = server().handle(parse("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}"));
        JsonNode result = r.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("Unknown tool"));
    }

    @Test
    public void pingReturnsEmptyResult() {
        JsonNode r = server().handle(parse("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}"));
        assertTrue(r.get("result").isObject());
        assertEquals(0, r.get("result").size());
    }

    @Test
    public void notificationReturnsNull() {
        assertNull(server().handle(parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
    }

    @Test
    public void unknownMethodWithIdReturnsMethodNotFound() {
        JsonNode r = server().handle(parse("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"bogus/method\"}"));
        assertEquals(-32601, r.get("error").get("code").asInt());
    }

    @Test
    public void handlerThrowsBecomesErrorResult() {
        SdkMcpTool boom = SdkMcpTool.of("boom", "throws", null, args -> {
            throw new IllegalStateException("kaboom");
        });
        InProcessMcpServer s = new InProcessMcpServer(SdkMcpServer.create("x", boom), M);
        JsonNode r = s.handle(parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"boom\",\"arguments\":{}}}"));
        assertTrue(r.get("result").get("isError").asBoolean());
        assertTrue(r.get("result").get("content").get(0).get("text").asText().contains("kaboom"));
    }
}
