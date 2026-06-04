package com.anthropic.claude.agent.internal;

import com.anthropic.claude.agent.message.SdkMessage;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bridges a {@link Flow.Publisher Publisher}-based {@code Query} to a blocking {@link Iterator},
 * powering the convenience methods ({@code blockingIterator} / {@code stream} / {@code collect}).
 *
 * <p>It requests {@code Long.MAX_VALUE} (no backpressure — a blocking consumer wants everything) and
 * buffers onto an unbounded queue. The iterator drains until the terminal signal; a terminal
 * {@code onError} is rethrown from {@code hasNext()}.
 */
public final class BlockingSubscriber implements Flow.Subscriber<SdkMessage> {

    private static final Object DONE = new Object();

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile Throwable error;
    private Flow.Subscription subscription;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(SdkMessage item) {
        queue.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
        this.error = throwable;
        queue.add(DONE);
    }

    @Override
    public void onComplete() {
        queue.add(DONE);
    }

    /** A blocking iterator over the delivered messages. */
    public Iterator<SdkMessage> iterator() {
        return new Iterator<>() {
            private Object pending;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (finished) {
                    return false;
                }
                if (pending == null) {
                    try {
                        pending = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        finished = true;
                        if (subscription != null) {
                            subscription.cancel();
                        }
                        return false;
                    }
                }
                if (pending == DONE) {
                    finished = true;
                    pending = null;
                    if (error != null) {
                        throw error instanceof RuntimeException re
                                ? re : new RuntimeException("query failed", error);
                    }
                    return false;
                }
                return true;
            }

            @Override
            public SdkMessage next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                SdkMessage m = (SdkMessage) pending;
                pending = null;
                return m;
            }
        };
    }
}
