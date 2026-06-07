package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class AgUiEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void textContentSerializesWithTypeAndFields() throws Exception {
        AgUiEvent e = new AgUiEvent.TextMessageContent("m1", "hello");
        String json = mapper.writeValueAsString(e);
        assertTrue(json, json.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""));
        assertTrue(json, json.contains("\"messageId\":\"m1\""));
        assertTrue(json, json.contains("\"delta\":\"hello\""));
    }

    @Test
    public void runStartedHasType() throws Exception {
        AgUiEvent e = new AgUiEvent.RunStarted("t1", "r1");
        String json = mapper.writeValueAsString(e);
        assertTrue(json, json.contains("\"type\":\"RUN_STARTED\""));
        assertTrue(json, json.contains("\"threadId\":\"t1\""));
        assertTrue(json, json.contains("\"runId\":\"r1\""));
    }

    @Test
    public void typeAccessorMatchesWire() {
        assertEquals("TOOL_CALL_START", new AgUiEvent.ToolCallStart("c1", "Read").type());
    }
}
