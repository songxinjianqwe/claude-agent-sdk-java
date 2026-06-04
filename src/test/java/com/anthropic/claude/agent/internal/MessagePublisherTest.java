package com.anthropic.claude.agent.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkMessages;
import com.anthropic.claude.agent.testkit.TestSubscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Phase 2: backpressure, terminal signalling, single-subscriber and Reactive-Streams rule checks. */
public class MessagePublisherTest {

    private static SdkMessage status(String subtype) {
        return SdkMessages.parse("{\"type\":\"system\",\"subtype\":\"" + subtype + "\"}");
    }

    private static final Runnable NOOP = () -> {
    };

    @Test
    public void demandGatingDeliversOnlyWhatIsRequested() {
        MessagePublisher pub = new MessagePublisher(1024, NOOP);
        for (int i = 0; i < 5; i++) {
            pub.emit(status("s" + i));
        }
        pub.complete();

        TestSubscriber sub = new TestSubscriber();
        pub.subscribe(sub);

        sub.request(2);
        sub.awaitCount(2, 2000);
        TestSubscriber.sleep(100);
        assertEquals("no more than requested", 2, sub.received.size());
        assertFalse(sub.completed);

        sub.request(2);
        sub.awaitCount(4, 2000);
        TestSubscriber.sleep(100);
        assertEquals(4, sub.received.size());

        sub.request(10);
        sub.awaitTerminal();
        assertTrue(sub.completed);
        assertEquals(5, sub.received.size());
    }

    @Test
    public void unboundedRequestDeliversAllThenCompletes() {
        MessagePublisher pub = new MessagePublisher(1024, NOOP);
        pub.emit(status("a"));
        pub.emit(status("b"));
        pub.complete();

        TestSubscriber sub = new TestSubscriber();
        pub.subscribe(sub);
        sub.request(Long.MAX_VALUE);
        sub.awaitTerminal();

        assertTrue(sub.completed);
        assertEquals(2, sub.received.size());
    }

    @Test
    public void producerBlocksWhenBufferFullThenDrains() throws InterruptedException {
        MessagePublisher pub = new MessagePublisher(1, NOOP); // capacity 1 → tight backpressure
        AtomicBoolean producerDone = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            pub.emit(status("m1"));
            pub.emit(status("m2"));
            pub.emit(status("m3"));
            pub.complete();
            producerDone.set(true);
        }, "test-producer");
        producer.start();

        TestSubscriber.sleep(200);
        assertFalse("producer must block once the 1-slot buffer is full", producerDone.get());

        TestSubscriber sub = new TestSubscriber();
        pub.subscribe(sub);
        sub.request(Long.MAX_VALUE);
        sub.awaitTerminal();

        assertTrue(sub.completed);
        assertEquals(3, sub.received.size());
        producer.join(2000);
        assertTrue("producer unblocked once consumed", producerDone.get());
    }

    @Test
    public void errorTerminalDeliveredAfterBufferedMessages() {
        MessagePublisher pub = new MessagePublisher(1024, NOOP);
        pub.emit(status("a"));
        pub.emit(status("b"));
        pub.error(new RuntimeException("boom"));

        TestSubscriber sub = new TestSubscriber();
        pub.subscribe(sub);
        sub.request(Long.MAX_VALUE);
        sub.awaitTerminal();

        assertEquals(2, sub.received.size());
        assertNotNull(sub.error);
        assertEquals("boom", sub.error.getMessage());
        assertFalse(sub.completed);
    }

    @Test
    public void secondSubscriberRejected() {
        MessagePublisher pub = new MessagePublisher(1024, NOOP);
        pub.complete();

        TestSubscriber first = new TestSubscriber();
        pub.subscribe(first);

        TestSubscriber second = new TestSubscriber();
        pub.subscribe(second);
        second.awaitTerminal();
        assertNotNull(second.error);
        assertTrue(second.error instanceof IllegalStateException);
    }

    @Test
    public void nonPositiveRequestSignalsError() {
        MessagePublisher pub = new MessagePublisher(1024, NOOP);
        pub.emit(status("a"));

        TestSubscriber sub = new TestSubscriber();
        pub.subscribe(sub);
        sub.request(0);
        sub.awaitTerminal();

        assertNotNull(sub.error);
        assertTrue(sub.error instanceof IllegalArgumentException);
    }

    @Test
    public void cancelInvokesOnCancelAndStopsDelivery() {
        AtomicBoolean cancelHookRan = new AtomicBoolean(false);
        MessagePublisher pub = new MessagePublisher(1024, () -> cancelHookRan.set(true));
        pub.emit(status("a"));
        pub.emit(status("b")); // no terminal — stream still "open"

        TestSubscriber sub = new TestSubscriber();
        pub.subscribe(sub);
        sub.request(1);
        sub.awaitCount(1, 2000);
        sub.cancel();
        TestSubscriber.sleep(150);

        assertTrue("onCancel hook ran", cancelHookRan.get());
        assertEquals("no delivery past the single requested item after cancel", 1, sub.received.size());
        assertFalse(sub.completed);
        assertNull(sub.error);
    }
}
