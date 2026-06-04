package com.anthropic.claude.agent.internal;

import com.anthropic.claude.agent.message.SdkMessage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Flow;

/**
 * A single-subscriber {@link Flow.Publisher} of {@link SdkMessage} with Reactive-Streams
 * backpressure.
 *
 * <p>Design:
 * <ul>
 *   <li><b>Backpressure toward the producer</b>: a {@link Semaphore} with {@code capacity} permits
 *       gates {@link #emit} — the transport reader thread blocks once {@code capacity} messages are
 *       buffered-but-undelivered, and that block propagates down the OS pipe to {@code claude}
 *       (its stdout writes stall), throttling a fast producer to a slow consumer.</li>
 *   <li><b>Serial, demand-respecting delivery</b>: a dedicated delivery thread pulls from an
 *       <i>unbounded</i> queue and calls {@code onNext} only up to outstanding demand. A permit is
 *       released after each delivery. Terminal signals ({@code onComplete}/{@code onError}) ride the
 *       same queue (so ordering after the last message is preserved) but are NOT permit-gated, so a
 *       full buffer can never drop or delay them.</li>
 * </ul>
 *
 * <p>Only one subscriber is allowed (mirrors the Node {@code Query} async generator); a second
 * subscribe is rejected via {@code onError}.
 */
public final class MessagePublisher implements Flow.Publisher<SdkMessage> {

    private static final Logger LOG = System.getLogger(MessagePublisher.class.getName());

    private static final Object COMPLETE = new Object();
    private static final Object ERROR = new Object();

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Semaphore space;
    private final Runnable onCancel;

    private final AtomicBoolean terminalEnqueued = new AtomicBoolean(false);
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private volatile Throwable error;

    /**
     * @param capacity max buffered-but-undelivered messages before {@link #emit} blocks (&gt;= 1)
     * @param onCancel run when the subscriber cancels (used to tear down the transport)
     */
    public MessagePublisher(int capacity, Runnable onCancel) {
        this.space = new Semaphore(Math.max(1, capacity));
        this.onCancel = onCancel == null ? () -> {
        } : onCancel;
    }

    /** Emit a message downstream. Blocks if the buffer is full (backpressure). No-op once terminated. */
    public void emit(SdkMessage message) {
        if (terminalEnqueued.get()) {
            return;
        }
        try {
            space.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "interrupted while awaiting buffer space; dropping message", e);
            return;
        }
        if (terminalEnqueued.get()) {
            space.release();
            return;
        }
        queue.add(message);
    }

    /** Signal normal completion (idempotent / first-terminal-wins). */
    public void complete() {
        if (terminalEnqueued.compareAndSet(false, true)) {
            queue.add(COMPLETE);
        }
    }

    /** Signal failure (idempotent / first-terminal-wins). */
    public void error(Throwable t) {
        if (terminalEnqueued.compareAndSet(false, true)) {
            this.error = t;
            queue.add(ERROR);
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super SdkMessage> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("subscriber");
        }
        if (!subscribed.compareAndSet(false, true)) {
            // Reactive Streams §1.10: reject extra subscribers.
            subscriber.onSubscribe(new NoopSubscription());
            subscriber.onError(new IllegalStateException(
                    "MessagePublisher supports a single subscriber (the Query is consumed once)"));
            return;
        }
        Delivery delivery = new Delivery(subscriber);
        subscriber.onSubscribe(delivery);
        delivery.startThread();
    }

    /** Subscription + delivery loop for the one subscriber. */
    private final class Delivery implements Flow.Subscription, Runnable {
        private final Flow.Subscriber<? super SdkMessage> subscriber;
        private final AtomicLong demand = new AtomicLong(0);
        private final Object demandLock = new Object();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile boolean badRequest = false;
        private volatile Throwable badRequestError;
        private Thread thread;

        Delivery(Flow.Subscriber<? super SdkMessage> subscriber) {
            this.subscriber = subscriber;
        }

        void startThread() {
            thread = new Thread(this, "claude-sdk-delivery");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                // §3.9: non-positive request must signal onError.
                // Publish the error BEFORE the flag: the delivery thread keys off `badRequest`, and
                // the flag's volatile write must act as the release barrier so a reader that sees
                // badRequest==true is guaranteed to also see a non-null badRequestError.
                badRequestError = new IllegalArgumentException(
                        "Reactive Streams §3.9: request must be > 0, was " + n);
                badRequest = true;
                wakeAndInterrupt();
                return;
            }
            synchronized (demandLock) {
                long next = demand.get() + n;
                demand.set(next < 0 ? Long.MAX_VALUE : next); // saturate on overflow
                demandLock.notifyAll();
            }
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                wakeAndInterrupt();
                try {
                    onCancel.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "onCancel hook threw", e);
                }
            }
        }

        private void wakeAndInterrupt() {
            synchronized (demandLock) {
                demandLock.notifyAll();
            }
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    if (terminalCheck()) {
                        return;
                    }
                    Object item = queue.take();
                    if (item == COMPLETE) {
                        subscriber.onComplete();
                        return;
                    }
                    if (item == ERROR) {
                        subscriber.onError(error);
                        return;
                    }
                    awaitDemand();
                    if (terminalCheck()) {
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    SdkMessage msg = (SdkMessage) item;
                    subscriber.onNext(msg);
                    space.release();
                    if (demand.get() != Long.MAX_VALUE) {
                        demand.decrementAndGet();
                    }
                }
            } catch (InterruptedException ie) {
                if (badRequest && !cancelled.get()) {
                    subscriber.onError(badRequestError);
                }
                // otherwise cancelled → silent
            } catch (RuntimeException re) {
                LOG.log(Level.ERROR, "subscriber callback threw; terminating delivery", re);
            }
        }

        /** @return true if delivery must stop now (cancelled, or bad request signalled). */
        private boolean terminalCheck() {
            if (cancelled.get()) {
                return true;
            }
            if (badRequest) {
                subscriber.onError(badRequestError);
                return true;
            }
            return false;
        }

        private void awaitDemand() throws InterruptedException {
            synchronized (demandLock) {
                while (demand.get() == 0 && !cancelled.get() && !badRequest) {
                    demandLock.wait();
                }
            }
        }
    }

    private static final class NoopSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }
}
