package com.anthropic.claude.agent.transport;

import java.io.IOException;

/**
 * The bidirectional NDJSON channel to a {@code claude} process. Abstracted so the query/stream
 * logic can be unit-tested against an in-memory fake while production uses {@link ProcessTransport}
 * (a real spawned subprocess).
 *
 * <p>Threading contract: {@link #writeLine}/{@link #endInput} may be called from any thread and are
 * serialized internally (single writer). {@link Listener} callbacks are invoked on the transport's
 * own reader thread; the listener must not block it for long.
 */
public interface Transport extends AutoCloseable {

    /** Spawn/connect and begin pumping output to {@code listener}. Call exactly once. */
    void start(Listener listener) throws IOException;

    /** Enqueue one NDJSON line (a trailing newline is added) to the process stdin. */
    void writeLine(String ndjson);

    /** Close stdin (EOF) to signal no more input. */
    void endInput();

    /** True while the underlying process is alive. */
    boolean isAlive();

    /** Force-terminate the process and release all resources. Idempotent. */
    @Override
    void close();

    /** Callbacks fired as the transport reads from the process. */
    interface Listener {
        /** One raw line read from stdout (no trailing newline). */
        void onLine(String line);

        /** The process exited with {@code code}. Fired at most once. */
        void onExit(int code);

        /** A fatal transport error (spawn/read/write failure). Fired at most once. */
        void onError(Throwable t);
    }
}
