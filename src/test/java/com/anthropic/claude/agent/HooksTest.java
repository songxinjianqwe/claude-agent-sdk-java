package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.hooks.HookEvent;
import com.anthropic.claude.agent.hooks.HookOutput;
import com.anthropic.claude.agent.testkit.FakeTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Phase 6: hooks are registered in the initialize handshake and dispatched on hook_callback. */
public class HooksTest {

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
    public void hookEventWireNamesRoundTrip() {
        assertEquals("PreToolUse", HookEvent.PRE_TOOL_USE.wire());
        assertEquals("PostToolUse", HookEvent.POST_TOOL_USE.wire());
        assertEquals("PostToolBatch", HookEvent.POST_TOOL_BATCH.wire());
        assertEquals("MessageDisplay", HookEvent.MESSAGE_DISPLAY.wire());
        assertEquals(HookEvent.SESSION_START, HookEvent.fromWire("SessionStart"));
        assertEquals(HookEvent.USER_PROMPT_EXPANSION, HookEvent.fromWire("UserPromptExpansion"));
        // Matches sdk.d.ts:802 — 30 HookEvent members.
        assertEquals(30, HookEvent.values().length);
    }

    @Test
    public void hooksRegisteredInInitializeAndCallbackDispatched() {
        FakeTransport t = new FakeTransport();
        AtomicReference<String> seenToolName = new AtomicReference<>();
        AtomicReference<String> seenToolUseId = new AtomicReference<>();

        Options opts = Options.builder()
                .hook(HookEvent.PRE_TOOL_USE, "Bash", (input, toolUseId) -> {
                    seenToolName.set(input.get("tool_name").asText());
                    seenToolUseId.set(toolUseId);
                    return HookOutput.block("not allowed");
                })
                .build();
        ClaudeAgent.streamingQuery(opts, t);

        // initialize handshake was sent with the hooks map
        JsonNode init = parse(t.written.get(0));
        assertEquals("initialize", init.get("request").get("subtype").asText());
        JsonNode pre = init.get("request").get("hooks").get("PreToolUse");
        assertEquals("Bash", pre.get(0).get("matcher").asText());
        String cbId = pre.get(0).get("hookCallbackIds").get(0).asText();

        // CLI fires the hook
        t.feedLine("{\"type\":\"control_request\",\"request_id\":\"hc-1\",\"request\":"
                + "{\"subtype\":\"hook_callback\",\"callback_id\":\"" + cbId + "\","
                + "\"input\":{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Bash\"},"
                + "\"tool_use_id\":\"tu1\"}}");

        awaitWritten(t, 2);
        JsonNode resp = parse(t.written.get(t.written.size() - 1));
        assertEquals("control_response", resp.get("type").asText());
        assertEquals("hc-1", resp.get("response").get("request_id").asText());
        JsonNode payload = resp.get("response").get("response");
        assertEquals("block", payload.get("decision").asText());
        assertEquals("not allowed", payload.get("reason").asText());

        assertEquals("Bash", seenToolName.get());
        assertEquals("tu1", seenToolUseId.get());
    }

    @Test
    public void asyncHookOutputSerializesAsAsyncVariant() {
        FakeTransport t = new FakeTransport();
        Options opts = Options.builder()
                .hook(HookEvent.PRE_TOOL_USE, (input, toolUseId) -> HookOutput.async(30))
                .build();
        ClaudeAgent.streamingQuery(opts, t);

        String cbId = parse(t.written.get(0)).get("request").get("hooks")
                .get("PreToolUse").get(0).get("hookCallbackIds").get(0).asText();
        t.feedLine("{\"type\":\"control_request\",\"request_id\":\"a1\",\"request\":"
                + "{\"subtype\":\"hook_callback\",\"callback_id\":\"" + cbId + "\",\"input\":{}}}");

        awaitWritten(t, 2);
        JsonNode payload = parse(t.written.get(t.written.size() - 1))
                .get("response").get("response");
        assertTrue(payload.get("async").asBoolean());
        assertEquals(30, payload.get("asyncTimeout").asInt());
    }

    @Test
    public void unknownHookCallbackIdRepliesEmpty() {
        FakeTransport t = new FakeTransport();
        Options opts = Options.builder()
                .hook(HookEvent.POST_TOOL_USE, (input, toolUseId) -> HookOutput.cont())
                .build();
        ClaudeAgent.streamingQuery(opts, t);
        int before = t.written.size();

        t.feedLine("{\"type\":\"control_request\",\"request_id\":\"hc-x\",\"request\":"
                + "{\"subtype\":\"hook_callback\",\"callback_id\":\"does-not-exist\",\"input\":{}}}");

        awaitWritten(t, before + 1);
        JsonNode resp = parse(t.written.get(t.written.size() - 1));
        assertEquals("success", resp.get("response").get("subtype").asText());
        assertEquals("hc-x", resp.get("response").get("request_id").asText());
        assertTrue("empty payload", resp.get("response").get("response").isObject());
        assertEquals(0, resp.get("response").get("response").size());
    }

    @Test
    public void noHooksMeansNoInitializeAtStart() {
        FakeTransport t = new FakeTransport();
        ClaudeAgent.streamingQuery(Options.defaults(), t);
        // Without hooks (or MCP), the SDK does not send initialize eagerly.
        assertEquals(0, t.written.size());
    }
}
