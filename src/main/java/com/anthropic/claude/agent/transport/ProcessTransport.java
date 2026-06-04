package com.anthropic.claude.agent.transport;

import com.anthropic.claude.agent.Options;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Transport} backed by a real {@code claude} subprocess (stdin/stdout pipes), the
 * production transport behind {@link com.anthropic.claude.agent.ClaudeAgent#query}.
 *
 * <p>Lifecycle is deliberately simple — parent/child stdio, child dies when the parent exits — to
 * avoid the FIFO double-redirection exit-timing trap seen elsewhere. Exit is detected via stdout
 * EOF + {@link Process#waitFor()}. stderr is drained on its own thread (an undrained stderr buffer
 * would deadlock the child) and the last lines are retained for diagnostics.
 */
public final class ProcessTransport implements Transport {

    private static final Logger LOG = System.getLogger(ProcessTransport.class.getName());
    private static final int STDERR_TAIL_LINES = 50;

    // JS line separators. Built via (char) casts: writing   literally in Java source is itself
    // a source line terminator and would break compilation (a classic Java gotcha).
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);
    private static final String PARAGRAPH_SEPARATOR = String.valueOf((char) 0x2029);

    private final Options options;

    private Process process;
    private Writer stdin;
    private Listener listener;
    private ExecutorService writer;
    private Thread readerThread;
    private Thread stderrThread;

    private final AtomicBoolean closedByUs = new AtomicBoolean(false);
    private final AtomicBoolean exitSignaled = new AtomicBoolean(false);
    private final Deque<String> stderrTail = new ArrayDeque<>();

    public ProcessTransport(Options options) {
        this.options = options;
    }

    @Override
    public void start(Listener listener) throws IOException {
        this.listener = listener;
        ProcessBuilder pb = new ProcessBuilder(options.command());
        if (options.cwd() != null && !options.cwd().isBlank()) {
            pb.directory(new java.io.File(options.cwd()));
        }
        if (!options.env().isEmpty() || !options.unsetEnv().isEmpty()) {
            Map<String, String> environment = pb.environment();
            environment.putAll(options.env());
            // Remove inherited vars the caller wants gone (Node SDK env=undefined). Applied after the
            // puts so an unset always wins — e.g. stripping CLAUDECODE/TMUX when spawning from a CC session.
            for (String key : options.unsetEnv()) {
                environment.remove(key);
            }
        }
        pb.redirectErrorStream(false);

        LOG.log(Level.DEBUG, () -> "spawning: " + String.join(" ", options.command()));
        this.process = pb.start();
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.writer = Executors.newSingleThreadExecutor(r -> daemon(r, "claude-sdk-writer"));

        this.readerThread = new Thread(this::readStdout, "claude-sdk-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        this.stderrThread = new Thread(this::readStderr, "claude-sdk-stderr");
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    listener.onLine(line);
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "listener.onLine threw", e);
                }
            }
        } catch (IOException e) {
            if (!closedByUs.get()) {
                LOG.log(Level.WARNING, "stdout read error", e);
            }
        }
        // stdout closed → the process is exiting; wait for the code and signal once.
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        signalExit(code);
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rememberStderr(line);
                if (options.stderr() != null) {
                    try {
                        options.stderr().accept(line);
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "stderr consumer threw", e);
                    }
                }
            }
        } catch (IOException e) {
            if (!closedByUs.get()) {
                LOG.log(Level.DEBUG, "stderr read ended", e);
            }
        }
    }

    private void rememberStderr(String line) {
        synchronized (stderrTail) {
            stderrTail.addLast(line);
            while (stderrTail.size() > STDERR_TAIL_LINES) {
                stderrTail.removeFirst();
            }
        }
    }

    private String stderrTail() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }

    private void signalExit(int code) {
        if (!exitSignaled.compareAndSet(false, true)) {
            return;
        }
        if (closedByUs.get()) {
            return; // we tore it down on purpose; downstream is already terminal
        }
        if (code == 0) {
            listener.onExit(0);
        } else {
            listener.onError(new TransportException(
                    "claude process exited with code " + code, code, stderrTail()));
        }
    }

    @Override
    public void writeLine(String ndjson) {
        final String safe = escapeLineSeparators(ndjson);
        writer.execute(() -> {
            try {
                stdin.write(safe);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                if (!closedByUs.get()) {
                    LOG.log(Level.WARNING, "failed to write to claude stdin", e);
                }
            }
        });
    }

    @Override
    public void endInput() {
        writer.execute(() -> {
            try {
                stdin.close();
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "error closing stdin", e);
            }
        });
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public void close() {
        if (!closedByUs.compareAndSet(false, true)) {
            return;
        }
        if (writer != null) {
            writer.shutdownNow();
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
    }

    /**
     * Escape the JS line separators U+2028/U+2029 to their JSON {@code \\u202X} form. The receiving
     * CLI is JS and splits the NDJSON stream on these code points; leaving them raw inside a string
     * value would truncate the message (wire-protocol.md §E). Structural JSON chars are ASCII, so a
     * raw replace stays valid JSON.
     */
    static String escapeLineSeparators(String line) {
        if (line.indexOf((char) 0x2028) < 0 && line.indexOf((char) 0x2029) < 0) {
            return line;
        }
        return line.replace(LINE_SEPARATOR, "\\u2028").replace(PARAGRAPH_SEPARATOR, "\\u2029");
    }
}
