package com.anthropic.claude.agent.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.ControlException;
import com.anthropic.claude.agent.permission.CanUseTool;
import com.anthropic.claude.agent.permission.PermissionResult;
import com.anthropic.claude.agent.testkit.FakeTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Phase 4: bidirectional control protocol — envelopes, request/response correlation, can_use_tool. */
public class ControlProtocolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode lastWritten(FakeTransport t) {
        return parse(t.written.get(t.written.size() - 1));
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

    // --- SDK → CLI -------------------------------------------------------------------------------

    @Test
    public void sendRequestWritesEnvelopeAndCompletesOnSuccess() throws Exception {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, null);

        CompletableFuture<JsonNode> f = c.sendRequest("set_model", req -> req.put("model", "opus"));

        JsonNode sent = lastWritten(t);
        assertEquals("control_request", sent.get("type").asText());
        assertNotNull(sent.get("request_id"));
        assertEquals("set_model", sent.get("request").get("subtype").asText());
        assertEquals("opus", sent.get("request").get("model").asText());

        String id = sent.get("request_id").asText();
        c.onControlLine(parse("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\","
                + "\"request_id\":\"" + id + "\",\"response\":{\"ok\":true}}}"));

        JsonNode payload = f.get(2, TimeUnit.SECONDS);
        assertTrue(payload.get("ok").asBoolean());
    }

    @Test
    public void emptyPayloadSuccessResolvesToNull() throws Exception {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, null);
        CompletableFuture<JsonNode> f = c.sendRequest("interrupt", null);
        String id = lastWritten(t).get("request_id").asText();
        c.onControlLine(parse("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\","
                + "\"request_id\":\"" + id + "\"}}"));
        // success with no nested response payload → null
        assertEquals(null, f.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void errorResponseFailsFutureWithControlException() {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, null);
        CompletableFuture<JsonNode> f = c.sendRequest("set_permission_mode", req -> req.put("mode", "bypassPermissions"));
        String id = lastWritten(t).get("request_id").asText();
        c.onControlLine(parse("{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
                + "\"request_id\":\"" + id + "\",\"error\":\"not allowed\"}}"));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ControlException);
        assertTrue(ex.getCause().getMessage().contains("not allowed"));
    }

    @Test
    public void responseRequestIdCamelCaseTolerated() throws Exception {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, null);
        CompletableFuture<JsonNode> f = c.sendRequest("interrupt", null);
        String id = lastWritten(t).get("request_id").asText();
        // CLI (old client) sends camelCase requestId inside response
        c.onControlLine(parse("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\","
                + "\"requestId\":\"" + id + "\",\"response\":{}}}"));
        assertNotNull(f.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void rejectAllPendingFailsInFlightRequests() {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, null);
        CompletableFuture<JsonNode> f = c.sendRequest("interrupt", null);
        c.rejectAllPending(new ControlException("stream closed"));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ControlException);
    }

    // --- CLI → SDK (can_use_tool) ----------------------------------------------------------------

    @Test
    public void canUseToolAllowWritesCorrectResponse() {
        FakeTransport t = new FakeTransport();
        AtomicReference<String> seenTool = new AtomicReference<>();
        AtomicReference<String> seenCommand = new AtomicReference<>();
        AtomicReference<String> seenToolUseId = new AtomicReference<>();

        CanUseTool cb = (toolName, input, ctx) -> {
            seenTool.set(toolName);
            seenCommand.set(input.get("command").asText());
            seenToolUseId.set(ctx.toolUseId());
            return PermissionResult.allow(); // no input modification → updatedInput {}
        };
        ControlProtocol c = new ControlProtocol(t, M, cb);

        c.onControlLine(parse("{\"type\":\"control_request\",\"request_id\":\"perm-1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{\"command\":\"ls -la\"},"
                + "\"tool_use_id\":\"toolu_1\"}}"));

        awaitWritten(t, 1);
        JsonNode resp = lastWritten(t);
        assertEquals("control_response", resp.get("type").asText());
        assertEquals("success", resp.get("response").get("subtype").asText());
        assertEquals("perm-1", resp.get("response").get("request_id").asText());
        JsonNode payload = resp.get("response").get("response");
        assertEquals("allow", payload.get("behavior").asText());
        assertTrue("updatedInput present (required)", payload.has("updatedInput"));
        assertTrue(payload.get("updatedInput").isObject());
        assertEquals("toolu_1", payload.get("toolUseID").asText()); // camelCase in response (§E.9)

        assertEquals("Bash", seenTool.get());
        assertEquals("ls -la", seenCommand.get());
        assertEquals("toolu_1", seenToolUseId.get());
    }

    @Test
    public void canUseToolAllowWithUpdatedInput() {
        FakeTransport t = new FakeTransport();
        CanUseTool cb = (toolName, input, ctx) ->
                PermissionResult.allow(M.createObjectNode().put("command", "ls"));
        ControlProtocol c = new ControlProtocol(t, M, cb);

        c.onControlLine(parse("{\"type\":\"control_request\",\"request_id\":\"p2\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{\"command\":\"rm -rf /\"},"
                + "\"tool_use_id\":\"tu2\"}}"));

        awaitWritten(t, 1);
        JsonNode payload = lastWritten(t).get("response").get("response");
        assertEquals("allow", payload.get("behavior").asText());
        assertEquals("ls", payload.get("updatedInput").get("command").asText());
    }

    @Test
    public void canUseToolResultCanOverrideToolUseId() {
        FakeTransport t = new FakeTransport();
        CanUseTool cb = (toolName, input, ctx) ->
                new PermissionResult.Deny("no", false, null, "custom-id");
        ControlProtocol c = new ControlProtocol(t, M, cb);

        c.onControlLine(parse("{\"type\":\"control_request\",\"request_id\":\"p9\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{},\"tool_use_id\":\"orig\"}}"));

        awaitWritten(t, 1);
        JsonNode payload = lastWritten(t).get("response").get("response");
        assertEquals("custom-id", payload.get("toolUseID").asText());
    }

    @Test
    public void canUseToolDenyWithInterrupt() {
        FakeTransport t = new FakeTransport();
        CanUseTool cb = (toolName, input, ctx) -> PermissionResult.deny("blocked", true);
        ControlProtocol c = new ControlProtocol(t, M, cb);

        c.onControlLine(parse("{\"type\":\"control_request\",\"request_id\":\"p3\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{},\"tool_use_id\":\"tu3\"}}"));

        awaitWritten(t, 1);
        JsonNode payload = lastWritten(t).get("response").get("response");
        assertEquals("deny", payload.get("behavior").asText());
        assertEquals("blocked", payload.get("message").asText());
        assertTrue(payload.get("interrupt").asBoolean());
    }

    @Test
    public void elicitationInvokesCallbackAndReturnsAction() {
        FakeTransport t = new FakeTransport();
        com.anthropic.claude.agent.elicitation.OnElicitation onElicit = req ->
                com.anthropic.claude.agent.elicitation.ElicitationResult.accept(
                        M.createObjectNode().put("answer", "yes"));
        ControlProtocol c = new ControlProtocol(t, M, null, onElicit);

        c.onControlLine(parse("{\"type\":\"control_request\",\"request_id\":\"e1\",\"request\":"
                + "{\"subtype\":\"elicitation\",\"mcp_server_name\":\"srv\",\"message\":\"ok?\","
                + "\"mode\":\"form\"}}"));

        awaitWritten(t, 1);
        JsonNode payload = lastWritten(t).get("response").get("response");
        assertEquals("accept", payload.get("action").asText());
        assertEquals("yes", payload.get("content").get("answer").asText());
    }

    @Test
    public void elicitationWithoutCallbackCancels() {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, null, null);
        c.onControlLine(parse("{\"type\":\"control_request\",\"request_id\":\"e2\",\"request\":"
                + "{\"subtype\":\"elicitation\",\"mcp_server_name\":\"srv\",\"message\":\"x\"}}"));
        awaitWritten(t, 1);
        assertEquals("cancel", lastWritten(t).get("response").get("response").get("action").asText());
    }

    @Test
    public void duplicateInboundRequestHandledOnce() {
        FakeTransport t = new FakeTransport();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        CanUseTool cb = (toolName, input, ctx) -> {
            calls.incrementAndGet();
            return PermissionResult.allow();
        };
        ControlProtocol c = new ControlProtocol(t, M, cb);

        String req = "{\"type\":\"control_request\",\"request_id\":\"dup\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Read\",\"input\":{},\"tool_use_id\":\"x\"}}";
        c.onControlLine(parse(req));
        c.onControlLine(parse(req)); // duplicate

        awaitWritten(t, 1);
        // give a moment to ensure no second response is produced
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        assertEquals("callback invoked once", 1, calls.get());
        assertEquals("only one response written", 1, t.written.size());
    }

    @Test
    public void inboundRequestIdCamelCaseTolerated() {
        FakeTransport t = new FakeTransport();
        ControlProtocol c = new ControlProtocol(t, M, (n, i, ctx) -> PermissionResult.allow());
        // CLI sends camelCase requestId at the top level
        c.onControlLine(parse("{\"type\":\"control_request\",\"requestId\":\"camel-1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Read\",\"input\":{},\"tool_use_id\":\"y\"}}"));
        awaitWritten(t, 1);
        assertEquals("camel-1", lastWritten(t).get("response").get("request_id").asText());
    }
}
