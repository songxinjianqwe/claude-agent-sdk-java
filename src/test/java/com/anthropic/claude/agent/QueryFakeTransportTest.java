package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.message.SdkAssistantMessage;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.message.SdkSystemMessage;
import com.anthropic.claude.agent.testkit.FakeTransport;
import com.anthropic.claude.agent.testkit.TestSubscriber;
import com.anthropic.claude.agent.transport.TransportException;
import java.time.Duration;
import java.util.List;
import org.junit.Test;

/** Phase 2: QueryImpl line routing and the one-shot input handshake, over an in-memory transport. */
public class QueryFakeTransportTest {

    private static final String SYSTEM_INIT =
            "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"m\",\"session_id\":\"s\"}";
    private static final String ASSISTANT =
            "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"hi\"}]},\"session_id\":\"s\"}";
    private static final String RESULT =
            "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"hi\",\"session_id\":\"s\"}";

    @Test
    public void happyPathStreamsMessagesInOrderAndCompletes() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hello", Options.defaults(), t);

        t.feedLine(SYSTEM_INIT);
        t.feedLine(ASSISTANT);
        t.feedLine(RESULT);
        t.feedExit(0);

        List<SdkMessage> msgs = q.collect();
        assertEquals(3, msgs.size());
        assertTrue(msgs.get(0) instanceof SdkSystemMessage);
        assertTrue(msgs.get(1) instanceof SdkAssistantMessage);
        assertTrue(msgs.get(2) instanceof SdkResultMessage);
        assertEquals("hi", ((SdkResultMessage) msgs.get(2)).result());
    }

    @Test
    public void oneShotWritesUserMessageAndClosesStdinOnlyAfterResult() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("summarize the file", Options.defaults(), t);

        assertEquals(1, t.written.size());
        String sent = t.written.get(0);
        assertTrue(sent.contains("\"type\":\"user\""));
        assertTrue(sent.contains("\"role\":\"user\""));
        assertTrue(sent.contains("summarize the file"));
        // stdin must stay open during the turn so tool-permission control responses can flow.
        assertFalse("stdin open until result", t.endInputCalled);

        t.feedLine(RESULT);
        assertTrue("stdin closed after result arrives", t.endInputCalled);

        t.feedExit(0);
        q.collect();
    }

    @Test
    public void controlLinesAreNotEmittedAsMessages() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);

        t.feedLine("{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\"}}");
        t.feedLine(ASSISTANT);
        t.feedLine("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"r1\"}}");
        t.feedLine(RESULT);
        t.feedExit(0);

        List<SdkMessage> msgs = q.collect();
        assertEquals("control lines filtered out", 2, msgs.size());
        assertTrue(msgs.get(0) instanceof SdkAssistantMessage);
        assertTrue(msgs.get(1) instanceof SdkResultMessage);
    }

    @Test
    public void malformedAndNonObjectLinesAreSkipped() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);

        t.feedLine("{this is not json");
        t.feedLine("123");
        t.feedLine("   ");
        t.feedLine(ASSISTANT);
        t.feedLine(RESULT);
        t.feedExit(0);

        List<SdkMessage> msgs = q.collect();
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0) instanceof SdkAssistantMessage);
    }

    @Test
    public void abnormalExitSurfacesAsError() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);

        t.feedLine(ASSISTANT);
        t.feedError(new TransportException("claude process exited with code 1", 1, "boom"));

        // collect() drains then rethrows the terminal error.
        assertThrows(TransportException.class, q::collect);
    }

    @Test
    public void abnormalExitDeliversBufferedMessagesBeforeError() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);

        TestSubscriber sub = new TestSubscriber();
        q.subscribe(sub);
        sub.request(Long.MAX_VALUE);

        t.feedLine(ASSISTANT);
        t.feedError(new TransportException("exit 1", 1, "boom"));
        sub.awaitTerminal();

        assertEquals(1, sub.received.size());
        assertTrue(sub.error instanceof TransportException);
        assertFalse(sub.completed);
    }

    @Test
    public void collectWithTimeoutReturnsWhenQueryFinishesInTime() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);
        t.feedLine(ASSISTANT);
        t.feedLine(RESULT);
        t.feedExit(0);

        List<SdkMessage> msgs = q.collect(Duration.ofSeconds(5));
        assertEquals(2, msgs.size());
    }

    @Test
    public void collectWithTimeoutAbortsAndClosesWhenQueryHangs() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);
        t.feedLine(ASSISTANT);   // a message arrives but no result/exit ever comes → the stream hangs

        assertThrows(QueryTimeoutException.class, () -> q.collect(Duration.ofMillis(200)));
        assertTrue("transport must be closed on timeout", t.closed);
    }

    @Test
    public void collectWithTimeoutPropagatesStreamError() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);
        t.feedLine(ASSISTANT);
        t.feedError(new TransportException("exit 1", 1, "boom"));

        assertThrows(TransportException.class, () -> q.collect(Duration.ofSeconds(5)));
    }

    @Test
    public void collectWithNonPositiveTimeoutRejected() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);
        assertThrows(IllegalArgumentException.class, () -> q.collect(Duration.ZERO));
    }
}
