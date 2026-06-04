package com.anthropic.claude.agent;

/**
 * Thrown / propagated when a control request to {@code claude} fails: the CLI returned an error
 * control response, or the control channel closed (stdin EOF) before a response arrived. Surfaces
 * from {@code Query} control methods (interrupt, setModel, …).
 */
public class ControlException extends RuntimeException {

    public ControlException(String message) {
        super(message);
    }

    public ControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
