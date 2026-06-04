package com.anthropic.claude.agent.testkit;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * A trivial synchronous, single-subscriber {@link Flow.Publisher} over a fixed list — for
 * deterministic tests of reactive input. On {@code request(n)} it delivers up to {@code n} items
 * synchronously and calls {@code onComplete} when drained.
 */
public final class IterablePublisher<T> implements Flow.Publisher<T> {

    private final List<T> items;

    public IterablePublisher(List<T> items) {
        this.items = items;
    }

    @SafeVarargs
    public static <T> IterablePublisher<T> of(T... items) {
        return new IterablePublisher<>(List.of(items));
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Flow.Subscription() {
            private int i = 0;
            private boolean cancelled = false;

            @Override
            public void request(long n) {
                while (n-- > 0 && i < items.size() && !cancelled) {
                    subscriber.onNext(items.get(i++));
                }
                if (i >= items.size() && !cancelled) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
