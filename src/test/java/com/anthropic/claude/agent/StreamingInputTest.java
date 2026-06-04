package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.testkit.FakeTransport;
import com.anthropic.claude.agent.testkit.IterablePublisher;
import org.junit.Test;

/** Phase 3: streaming (multi-turn) input — imperative and reactive — preserves order. */
public class StreamingInputTest {

    @Test
    public void imperativeStreamingWritesTurnsInOrderThenClosesStdin() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        // streamingQuery must NOT auto-send any input.
        assertEquals(0, t.written.size());

        q.streamInput("first");
        q.streamInput("second");
        q.endInput();

        assertEquals(2, t.written.size());
        assertTrue(t.written.get(0).contains("\"content\":\"first\""));
        assertTrue(t.written.get(1).contains("\"content\":\"second\""));
        assertTrue(t.endInputCalled);
    }

    @Test
    public void reactiveInputWritesTurnsInOrderAndClosesStdinOnComplete() {
        FakeTransport t = new FakeTransport();
        ClaudeAgent.query(IterablePublisher.of("alpha", "beta", "gamma"), Options.defaults(), t);

        assertEquals(3, t.written.size());
        assertTrue(t.written.get(0).contains("\"content\":\"alpha\""));
        assertTrue(t.written.get(1).contains("\"content\":\"beta\""));
        assertTrue(t.written.get(2).contains("\"content\":\"gamma\""));
        assertTrue("stdin closed when input publisher completed", t.endInputCalled);
    }
}
