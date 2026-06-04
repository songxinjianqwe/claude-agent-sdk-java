package com.anthropic.claude.agent.transport;

/**
 * A transport-level failure: spawn failure, I/O error on the pipes, or an abnormal (non-zero)
 * process exit. When the process exits non-zero, {@link #exitCode()} is set and {@link #stderrTail()}
 * carries the last captured stderr for diagnosis.
 */
public class TransportException extends RuntimeException {

    private final Integer exitCode;
    private final String stderrTail;

    public TransportException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = null;
        this.stderrTail = null;
    }

    public TransportException(String message, int exitCode, String stderrTail) {
        super(message + (stderrTail == null || stderrTail.isEmpty() ? "" : "\n--- stderr ---\n" + stderrTail));
        this.exitCode = exitCode;
        this.stderrTail = stderrTail;
    }

    /** Process exit code if this was an abnormal exit, else null. */
    public Integer exitCode() {
        return exitCode;
    }

    /** Last captured stderr (may be null/empty). */
    public String stderrTail() {
        return stderrTail;
    }
}
