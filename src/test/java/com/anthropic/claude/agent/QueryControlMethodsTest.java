package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.permission.PermissionMode;
import com.anthropic.claude.agent.testkit.FakeTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Phase 5: Query runtime control methods send the right control_request and resolve on response. */
public class QueryControlMethodsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The last written control_request's inner {@code request} node. */
    private static JsonNode lastRequest(FakeTransport t) {
        return parse(t.written.get(t.written.size() - 1)).get("request");
    }

    private static String lastRequestId(FakeTransport t) {
        return parse(t.written.get(t.written.size() - 1)).get("request_id").asText();
    }

    /** Feed a success control_response (with optional nested payload JSON) for the given id. */
    private static void respondSuccess(FakeTransport t, String id, String payloadJson) {
        String payload = payloadJson == null ? "" : ",\"response\":" + payloadJson;
        t.feedLine("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\""
                + id + "\"" + payload + "}}");
    }

    @Test
    public void interruptSendsAndResolves() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<Void> f = q.interrupt();
        assertEquals("interrupt", lastRequest(t).get("subtype").asText());
        respondSuccess(t, lastRequestId(t), null);
        assertNull(f.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void setPermissionModeSendsModeAndResolves() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<Void> f = q.setPermissionMode(PermissionMode.ACCEPT_EDITS);
        JsonNode req = lastRequest(t);
        assertEquals("set_permission_mode", req.get("subtype").asText());
        assertEquals("acceptEdits", req.get("mode").asText());
        respondSuccess(t, lastRequestId(t), null);
        f.get(2, TimeUnit.SECONDS);
    }

    @Test
    public void setModelSendsModel() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<Void> f = q.setModel("claude-opus-4-7");
        JsonNode req = lastRequest(t);
        assertEquals("set_model", req.get("subtype").asText());
        assertEquals("claude-opus-4-7", req.get("model").asText());
        respondSuccess(t, lastRequestId(t), null);
        f.get(2, TimeUnit.SECONDS);
    }

    @Test
    public void setMaxThinkingTokensVariants() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        q.setMaxThinkingTokens(2048);
        assertEquals(2048, lastRequest(t).get("max_thinking_tokens").asInt());
        respondSuccess(t, lastRequestId(t), null);

        q.setMaxThinkingTokens(0);
        assertEquals(0, lastRequest(t).get("max_thinking_tokens").asInt());
        respondSuccess(t, lastRequestId(t), null);

        q.setMaxThinkingTokens(null);
        assertTrue("null clears the budget", lastRequest(t).get("max_thinking_tokens").isNull());
    }

    @Test
    public void mcpServerStatusParsesList() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<List<McpServerStatus>> f = q.mcpServerStatus();
        assertEquals("mcp_status", lastRequest(t).get("subtype").asText());
        respondSuccess(t, lastRequestId(t),
                "{\"mcpServers\":[{\"name\":\"fs\",\"status\":\"connected\"},"
                        + "{\"name\":\"db\",\"status\":\"failed\"}]}");

        List<McpServerStatus> servers = f.get(2, TimeUnit.SECONDS);
        assertEquals(2, servers.size());
        assertEquals("fs", servers.get(0).name());
        assertEquals("connected", servers.get(0).status());
        assertEquals("failed", servers.get(1).status());
    }

    @Test
    public void initializationResultParsesModelsAndAccount() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<InitializeResult> f = q.initializationResult();
        assertEquals("initialize", lastRequest(t).get("subtype").asText());
        respondSuccess(t, lastRequestId(t),
                "{\"models\":[{\"value\":\"claude-opus-4-7\",\"displayName\":\"Opus\"}],"
                        + "\"output_style\":\"default\",\"pid\":4242,"
                        + "\"account\":{\"email\":\"a@b.com\",\"apiProvider\":\"firstParty\"}}");

        InitializeResult init = f.get(2, TimeUnit.SECONDS);
        assertEquals(1, init.models().size());
        assertEquals("claude-opus-4-7", init.models().get(0).value());
        assertEquals("default", init.outputStyle());
        assertEquals(Integer.valueOf(4242), init.pid());
        assertEquals("a@b.com", init.account().email());
        assertEquals("firstParty", init.account().apiProvider());
    }

    @Test
    public void initializationResultCachedSoSentOnce() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<InitializeResult> f1 = q.initializationResult();
        CompletableFuture<InitializeResult> f2 = q.initializationResult();
        // Only one initialize control_request was written.
        long inits = t.written.stream()
                .filter(l -> parse(l).path("request").path("subtype").asText().equals("initialize"))
                .count();
        assertEquals(1, inits);

        respondSuccess(t, lastRequestId(t), "{\"models\":[]}");
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
    }

    @Test
    public void mcpAndTaskControlMethodsSendCorrectRequests() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        CompletableFuture<Void> f1 = q.reconnectMcpServer("fs");
        assertEquals("mcp_reconnect", lastRequest(t).get("subtype").asText());
        assertEquals("fs", lastRequest(t).get("serverName").asText());
        respondSuccess(t, lastRequestId(t), null);
        f1.get(2, TimeUnit.SECONDS);

        CompletableFuture<Void> f2 = q.toggleMcpServer("fs", false);
        assertEquals("mcp_toggle", lastRequest(t).get("subtype").asText());
        assertFalse(lastRequest(t).get("enabled").asBoolean());
        respondSuccess(t, lastRequestId(t), null);
        f2.get(2, TimeUnit.SECONDS);

        CompletableFuture<Void> f3 = q.stopTask("task-1");
        assertEquals("stop_task", lastRequest(t).get("subtype").asText());
        assertEquals("task-1", lastRequest(t).get("task_id").asText());
        respondSuccess(t, lastRequestId(t), null);
        f3.get(2, TimeUnit.SECONDS);

        CompletableFuture<Void> f4 = q.applyFlagSettings(M.readTree("{\"model\":\"opus\"}"));
        assertEquals("apply_flag_settings", lastRequest(t).get("subtype").asText());
        assertEquals("opus", lastRequest(t).get("settings").get("model").asText());
        respondSuccess(t, lastRequestId(t), null);
        f4.get(2, TimeUnit.SECONDS);

        CompletableFuture<JsonNode> f5 = q.getSettings();
        assertEquals("get_settings", lastRequest(t).get("subtype").asText());
        respondSuccess(t, lastRequestId(t), "{\"effective\":{\"model\":\"opus\"}}");
        assertNotNull(f5.get(2, TimeUnit.SECONDS).get("effective"));
    }

    @Test
    public void backgroundTasksSendsRequestAndParsesBackgrounded() throws Exception {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        // with toolUseId
        CompletableFuture<Boolean> f1 = q.backgroundTasks("tu-1");
        assertEquals("background_tasks", lastRequest(t).get("subtype").asText());
        assertEquals("tu-1", lastRequest(t).get("tool_use_id").asText());
        respondSuccess(t, lastRequestId(t), "{\"backgrounded\":false}");
        assertFalse(f1.get(2, TimeUnit.SECONDS));

        // no-arg backgrounds all → tool_use_id omitted; missing 'backgrounded' defaults to true
        CompletableFuture<Boolean> f2 = q.backgroundTasks();
        assertFalse("tool_use_id omitted when null", lastRequest(t).has("tool_use_id"));
        respondSuccess(t, lastRequestId(t), "{}");
        assertTrue(f2.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void controlMethodAfterExitFailsFast() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);
        t.feedExit(0); // process ended

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> q.interrupt().get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ControlException);
    }
}
