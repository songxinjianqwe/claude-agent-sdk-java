package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.anthropic.claude.agent.agui.model.RunAgentInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class RunAgentInputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void lastUserTextReturnsLatestUserMessage() throws Exception {
        String json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":["
                + "{\"id\":\"1\",\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"id\":\"2\",\"role\":\"assistant\",\"content\":\"hello\"},"
                + "{\"id\":\"3\",\"role\":\"user\",\"content\":\"summarize README\"}]}";
        RunAgentInput in = mapper.readValue(json, RunAgentInput.class);
        assertEquals("summarize README", in.lastUserText());
        assertEquals("t1", in.threadId());
    }

    @Test
    public void lastUserTextNullWhenNoUserMessage() throws Exception {
        String json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":["
                + "{\"id\":\"1\",\"role\":\"assistant\",\"content\":\"hello\"}]}";
        RunAgentInput in = mapper.readValue(json, RunAgentInput.class);
        assertNull(in.lastUserText());
    }

    @Test
    public void toleratesUnknownFields() throws Exception {
        String json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"tools\":[],\"state\":{},"
                + "\"context\":[],\"messages\":[{\"id\":\"1\",\"role\":\"user\",\"content\":\"hi\"}]}";
        RunAgentInput in = mapper.readValue(json, RunAgentInput.class);
        assertEquals("hi", in.lastUserText());
    }
}
