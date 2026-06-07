package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.anthropic.claude.agent.message.ApiMessage;
import com.anthropic.claude.agent.message.ContentBlock;
import com.anthropic.claude.agent.message.SdkPartialAssistantMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.message.SdkUserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RunTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造一个 partial（stream_event）消息：event 为给定 JSON。 */
    private SdkPartialAssistantMessage partial(String eventJson) throws Exception {
        var event = mapper.readTree(eventJson);
        return new SdkPartialAssistantMessage(event, null, "sess-1", "uuid-1", event);
    }

    private RunTranslator newTranslator(List<AgUiEvent> sink, SessionStore store) {
        return new RunTranslator(sink::add, store, "thread-1", "run-1");
    }

    @Test
    public void textBlockProducesStartContentEnd() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}"));
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(4, out.size());
        AgUiEvent.TextMessageStart start = (AgUiEvent.TextMessageStart) out.get(0);
        assertEquals("assistant", start.role());
        AgUiEvent.TextMessageContent c1 = (AgUiEvent.TextMessageContent) out.get(1);
        assertEquals("Hel", c1.delta());
        assertEquals(start.messageId(), c1.messageId());
        AgUiEvent.TextMessageContent c2 = (AgUiEvent.TextMessageContent) out.get(2);
        assertEquals("lo", c2.delta());
        AgUiEvent.TextMessageEnd end = (AgUiEvent.TextMessageEnd) out.get(3);
        assertEquals(start.messageId(), end.messageId());
    }

    @Test
    public void toolUseProducesStartArgsEnd() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"file\\\":\"}}"));
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(3, out.size());
        AgUiEvent.ToolCallStart start = (AgUiEvent.ToolCallStart) out.get(0);
        assertEquals("toolu_1", start.toolCallId());
        assertEquals("Read", start.toolCallName());
        AgUiEvent.ToolCallArgs args = (AgUiEvent.ToolCallArgs) out.get(1);
        assertEquals("toolu_1", args.toolCallId());
        assertEquals("{\"file\":", args.delta());
        AgUiEvent.ToolCallEnd end = (AgUiEvent.ToolCallEnd) out.get(2);
        assertEquals("toolu_1", end.toolCallId());
    }

    @Test
    public void toolResultFromUserMessageProducesResult() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        var contentNode = mapper.readTree("\"file contents here\"");
        ContentBlock.ToolResult tr =
                new ContentBlock.ToolResult("toolu_1", contentNode, false, contentNode);
        ApiMessage apiMsg = new ApiMessage("user", null, null, List.of(tr), null, null, null);
        SdkUserMessage um = new SdkUserMessage("u1", "sess-1", null, apiMsg, null);

        t.onNext(um);

        assertEquals(1, out.size());
        AgUiEvent.ToolCallResult r = (AgUiEvent.ToolCallResult) out.get(0);
        assertEquals("toolu_1", r.toolCallId());
        assertEquals("file contents here", r.content());
    }

    @Test
    public void thinkingBlockProducesReasoningEvents() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        t.onNext(partial("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"let me think\"}}"));
        t.onNext(partial("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc==\"}}"));
        t.onNext(partial("{\"type\":\"content_block_stop\",\"index\":0}"));

        assertEquals(5, out.size());
        assertTrue(out.get(0) instanceof AgUiEvent.ReasoningStart);
        assertTrue(out.get(1) instanceof AgUiEvent.ReasoningMessageStart);
        AgUiEvent.ReasoningMessageContent c = (AgUiEvent.ReasoningMessageContent) out.get(2);
        assertEquals("let me think", c.delta());           // signature_delta 不产出事件
        assertTrue(out.get(3) instanceof AgUiEvent.ReasoningMessageEnd);
        assertTrue(out.get(4) instanceof AgUiEvent.ReasoningEnd);
    }

    @Test
    public void resultSuccessStoresSessionAndFinishes() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        SessionStore store = new SessionStore();
        RunTranslator t = newTranslator(out, store);

        var result = new SdkResultMessage(
                SdkResultMessage.Subtype.SUCCESS,
                false, "done", "sess-xyz", "u1", 1, 10L, 5L, 0.01, "end_turn", null, null,
                mapper.createObjectNode());
        t.onNext(result);

        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof AgUiEvent.RunFinished);
        assertEquals("sess-xyz", store.get("thread-1"));
    }

    @Test
    public void resultErrorProducesRunError() throws Exception {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());

        var result = new SdkResultMessage(
                SdkResultMessage.Subtype.ERROR_MAX_TURNS,
                true, null, "sess-err", "u1", 5, 1L, 1L, 0.0, null, null, null,
                mapper.createObjectNode());
        t.onNext(result);

        assertEquals(1, out.size());
        AgUiEvent.RunError e = (AgUiEvent.RunError) out.get(0);
        assertEquals("error_max_turns", e.code());
    }

    @Test
    public void onErrorProducesRunError() {
        List<AgUiEvent> out = new ArrayList<>();
        RunTranslator t = newTranslator(out, new SessionStore());
        t.onError(new RuntimeException("boom"));
        assertEquals(1, out.size());
        AgUiEvent.RunError e = (AgUiEvent.RunError) out.get(0);
        assertTrue(e.message().contains("boom"));
    }
}
