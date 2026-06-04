package com.anthropic.claude.agent.testkit;

import com.anthropic.claude.agent.transport.Transport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link Transport} for deterministic unit tests: it never spawns a process. Tests push
 * lines / exit / error via {@link #feedLine}/{@link #feedExit}/{@link #feedError} and assert on what
 * the SDK wrote ({@link #written}, {@link #endInputCalled}).
 */
public final class FakeTransport implements Transport {

    public final List<String> written = new CopyOnWriteArrayList<>();
    public volatile boolean endInputCalled;
    public volatile boolean closed;

    private volatile Listener listener;

    @Override
    public void start(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void writeLine(String ndjson) {
        written.add(ndjson);
    }

    @Override
    public void endInput() {
        endInputCalled = true;
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    // --- test drivers (simulate the claude side) -------------------------------------------------

    public void feedLine(String line) {
        listener.onLine(line);
    }

    public void feedExit(int code) {
        listener.onExit(code);
    }

    public void feedError(Throwable t) {
        listener.onError(t);
    }
}
