package com.anthropic.claude.agent.testkit;

import com.anthropic.claude.agent.message.SdkMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Flow.Subscriber} for tests that does NOT auto-request, so a test can drive demand
 * explicitly and assert backpressure. Records messages, terminal state, and the subscription.
 */
public final class TestSubscriber implements Flow.Subscriber<SdkMessage> {

    public final List<SdkMessage> received = new CopyOnWriteArrayList<>();
    public volatile Throwable error;
    public volatile boolean completed;

    private volatile Flow.Subscription subscription;
    private final CountDownLatch subscribed = new CountDownLatch(1);
    private final CountDownLatch terminal = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscribed.countDown();
    }

    @Override
    public void onNext(SdkMessage item) {
        received.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
        this.error = throwable;
        terminal.countDown();
    }

    @Override
    public void onComplete() {
        this.completed = true;
        terminal.countDown();
    }

    public void request(long n) {
        awaitSubscribed();
        subscription.request(n);
    }

    public void cancel() {
        awaitSubscribed();
        subscription.cancel();
    }

    public void awaitSubscribed() {
        await(subscribed, 5000, "onSubscribe");
    }

    public void awaitTerminal() {
        await(terminal, 10000, "terminal (onComplete/onError)");
    }

    /** Block until at least {@code n} messages have been received (or fail after timeoutMs). */
    public void awaitCount(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (received.size() < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(
                        "expected >= " + n + " messages but had " + received.size());
            }
            sleep(5);
        }
    }

    private static void await(CountDownLatch latch, long timeoutMs, String what) {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
