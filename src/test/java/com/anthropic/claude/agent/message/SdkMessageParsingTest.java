package com.anthropic.claude.agent.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Phase 1: parse real (captured from claude 2.1.159) and synthetic NDJSON lines into the correct
 * {@link SdkMessage} variants; verify unknown types fall back rather than throw, and malformed
 * input throws.
 */
public class SdkMessageParsingTest {

    private static String fixture(String name) {
        try (InputStream in = SdkMessageParsingTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull("missing fixture: " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static SdkMessage parseFixture(String name) {
        return SdkMessages.parse(fixture(name).strip());
    }

    // --- real captured fixtures ------------------------------------------------------------------

    @Test
    public void realAssistantTextParsesToAssistantWithTextBlock() {
        SdkMessage m = parseFixture("real_assistant_text.json");
        SdkAssistantMessage a = (SdkAssistantMessage) m;
        assertEquals("assistant", a.type());
        assertEquals("claude-haiku-4-5-20251001", a.message().model());
        assertEquals("assistant", a.message().role());
        assertEquals(1, a.message().content().size());
        assertTrue(a.message().content().get(0) instanceof ContentBlock.Text);
        assertEquals("hi", a.message().text());
        assertEquals("req_011CbcvR6EEeUYpW6WfxbUJS", a.requestId());
        assertNull(a.parentToolUseId());
        assertEquals("15f4a7b7-be1d-4ccb-b059-7a1109f71d09", a.sessionId());
    }

    @Test
    public void realAssistantThinkingParsesToThinkingBlockWithSignature() {
        SdkAssistantMessage a = (SdkAssistantMessage) parseFixture("real_assistant_thinking.json");
        assertEquals(1, a.message().content().size());
        ContentBlock.Thinking t = (ContentBlock.Thinking) a.message().content().get(0);
        assertTrue("thinking text present", t.thinking() != null && !t.thinking().isEmpty());
        assertNotNull("signature present (redacted thinking)", t.signature());
        assertEquals("no text blocks → text() empty", "", a.message().text());
    }

    @Test
    public void realResultSuccessParses() {
        SdkResultMessage r = (SdkResultMessage) parseFixture("real_result_success.json");
        assertEquals(SdkResultMessage.Subtype.SUCCESS, r.subtype());
        assertFalse(r.isError());
        assertEquals("hi", r.result());
        assertEquals(Integer.valueOf(1), r.numTurns());
        assertEquals("end_turn", r.stopReason());
        assertEquals(0.06732, r.totalCostUsd(), 1e-9);
        assertNotNull(r.usage());
        assertNotNull(r.modelUsage());
    }

    @Test
    public void realSystemInitParsesTypedView() {
        SdkSystemMessage s = (SdkSystemMessage) parseFixture("real_system_init.json");
        assertEquals("system", s.type());
        assertEquals("init", s.subtype());
        assertTrue(s.isInit());
        SdkSystemMessage.Init init = s.init();
        assertNotNull(init);
        assertEquals("claude-haiku-4-5-20251001", init.model());
        assertEquals("default", init.permissionMode());
        assertEquals("2.1.159", init.claudeCodeVersion());
        assertTrue("tools include Bash", init.tools().contains("Bash"));
        assertTrue("has mcp servers", init.mcpServers().size() >= 1);
        assertTrue(init.mcpServers().stream()
                .anyMatch(ms -> "chrome-devtools".equals(ms.name()) && "connected".equals(ms.status())));
    }

    @Test
    public void realSystemStatusIsNotInit() {
        SdkSystemMessage s = (SdkSystemMessage) parseFixture("real_system_status.json");
        assertEquals("status", s.subtype());
        assertFalse(s.isInit());
        assertNull(s.init());
        assertEquals("default", SdkMessage.text(s.raw(), "permissionMode"));
    }

    @Test
    public void realRateLimitEventParses() {
        SdkRateLimitMessage r = (SdkRateLimitMessage) parseFixture("real_rate_limit_event.json");
        assertEquals("rate_limit_event", r.type());
        assertEquals("allowed", SdkMessage.text(r.rateLimitInfo(), "status"));
        assertEquals("five_hour", SdkMessage.text(r.rateLimitInfo(), "rateLimitType"));
    }

    // --- synthetic fixtures ----------------------------------------------------------------------

    @Test
    public void syntheticAssistantToolUseParses() {
        SdkAssistantMessage a = (SdkAssistantMessage) parseFixture("syn_assistant_tooluse.json");
        assertEquals("tool_use", a.message().stopReason());
        assertEquals(2, a.message().content().size());
        assertTrue(a.message().content().get(0) instanceof ContentBlock.Text);
        ContentBlock.ToolUse tu = (ContentBlock.ToolUse) a.message().content().get(1);
        assertEquals("toolu_01", tu.id());
        assertEquals("Read", tu.name());
        assertEquals("/tmp/x", tu.input().get("file_path").asText());
    }

    @Test
    public void syntheticUserToolResultParses() {
        SdkUserMessage u = (SdkUserMessage) parseFixture("syn_user_toolresult.json");
        assertEquals("toolu_01", u.parentToolUseId());
        assertEquals(1, u.message().content().size());
        ContentBlock.ToolResult tr = (ContentBlock.ToolResult) u.message().content().get(0);
        assertEquals("toolu_01", tr.toolUseId());
        assertEquals(Boolean.FALSE, tr.isError());
        assertTrue(tr.content().isArray());
    }

    @Test
    public void syntheticUserTextStringContentNormalizedToTextBlock() {
        SdkUserMessage u = (SdkUserMessage) parseFixture("syn_user_text.json");
        assertEquals("user", u.message().role());
        assertEquals(1, u.message().content().size());
        ContentBlock.Text t = (ContentBlock.Text) u.message().content().get(0);
        assertEquals("hello there", t.text());
        assertEquals("hello there", u.message().text());
    }

    @Test
    public void syntheticResultErrorMaxTurnsParses() {
        SdkResultMessage r = (SdkResultMessage) parseFixture("syn_result_error_max_turns.json");
        assertEquals(SdkResultMessage.Subtype.ERROR_MAX_TURNS, r.subtype());
        assertTrue(r.isError());
        assertNull("error result has no result text", r.result());
        assertEquals(Integer.valueOf(5), r.numTurns());
    }

    @Test
    public void syntheticStreamEventParses() {
        SdkPartialAssistantMessage p =
                (SdkPartialAssistantMessage) parseFixture("syn_stream_event.json");
        assertEquals("stream_event", p.type());
        assertEquals("content_block_delta", p.eventType());
        assertEquals("hi", p.event().get("delta").get("text").asText());
        assertEquals("req_1", SdkMessage.text(p.raw(), "request_id"));
    }

    // --- drift tolerance + error handling --------------------------------------------------------

    @Test
    public void unknownTypeFallsBackWithoutThrowing() {
        SdkMessage m = parseFixture("syn_unknown_type.json");
        SdkUnknownMessage u = (SdkUnknownMessage) m;
        assertEquals("some_future_event", u.type());
        assertEquals("bar", u.raw().get("foo").asText());
        assertEquals("s1", u.sessionId());
        assertEquals("u6", u.uuid());
    }

    @Test
    public void missingTypeFallsBackToUnknown() {
        SdkMessage m = SdkMessages.parse("{\"hello\":\"world\"}");
        assertTrue(m instanceof SdkUnknownMessage);
        assertNull(((SdkUnknownMessage) m).type());
    }

    @Test
    public void malformedJsonThrows() {
        SdkMessageParseException ex =
                assertThrows(SdkMessageParseException.class, () -> SdkMessages.parse("{not json"));
        assertEquals("{not json", ex.line());
    }

    @Test
    public void blankLineThrows() {
        assertThrows(SdkMessageParseException.class, () -> SdkMessages.parse("   "));
    }

    @Test
    public void nonObjectJsonThrows() {
        assertThrows(SdkMessageParseException.class, () -> SdkMessages.parse("123"));
        assertThrows(SdkMessageParseException.class, () -> SdkMessages.parse("\"a string\""));
    }

    @Test
    public void extraTopLevelMessageTypesParse() {
        SdkToolProgressMessage tp = (SdkToolProgressMessage) SdkMessages.parse(
                "{\"type\":\"tool_progress\",\"tool_use_id\":\"t1\",\"tool_name\":\"Bash\","
                        + "\"elapsed_time_seconds\":2.5,\"session_id\":\"s\"}");
        assertEquals("t1", tp.toolUseId());
        assertEquals("Bash", tp.toolName());
        assertEquals(2.5, tp.elapsedTimeSeconds(), 1e-9);

        SdkToolUseSummaryMessage ts = (SdkToolUseSummaryMessage) SdkMessages.parse(
                "{\"type\":\"tool_use_summary\",\"summary\":\"did stuff\","
                        + "\"preceding_tool_use_ids\":[\"a\",\"b\"]}");
        assertEquals("did stuff", ts.summary());
        assertEquals(2, ts.precedingToolUseIds().size());

        SdkAuthStatusMessage as = (SdkAuthStatusMessage) SdkMessages.parse(
                "{\"type\":\"auth_status\",\"isAuthenticating\":true,\"output\":[\"x\"]}");
        assertTrue(as.authenticating());
        assertEquals(1, as.output().size());

        SdkPromptSuggestionMessage ps = (SdkPromptSuggestionMessage) SdkMessages.parse(
                "{\"type\":\"prompt_suggestion\",\"suggestion\":\"try this\"}");
        assertEquals("try this", ps.suggestion());
    }

    @Test
    public void resultSubtypeMappingIsExhaustiveAndDriftTolerant() {
        assertEquals(SdkResultMessage.Subtype.SUCCESS,
                SdkResultMessage.Subtype.fromWire("success"));
        assertEquals(SdkResultMessage.Subtype.ERROR_DURING_EXECUTION,
                SdkResultMessage.Subtype.fromWire("error_during_execution"));
        assertEquals(SdkResultMessage.Subtype.ERROR_MAX_BUDGET_USD,
                SdkResultMessage.Subtype.fromWire("error_max_budget_usd"));
        assertEquals(SdkResultMessage.Subtype.ERROR_MAX_STRUCTURED_OUTPUT_RETRIES,
                SdkResultMessage.Subtype.fromWire("error_max_structured_output_retries"));
        assertEquals(SdkResultMessage.Subtype.UNKNOWN,
                SdkResultMessage.Subtype.fromWire("something_new"));
        assertEquals(SdkResultMessage.Subtype.UNKNOWN, SdkResultMessage.Subtype.fromWire(null));
    }
}
