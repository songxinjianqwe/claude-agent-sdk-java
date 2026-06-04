package com.anthropic.claude.agent.message;

/**
 * Thrown when a stream-json line is not valid JSON. Unknown <em>message types</em> do NOT throw —
 * they yield {@link SdkUnknownMessage}. This is reserved for genuinely malformed (non-JSON) input.
 */
public class SdkMessageParseException extends RuntimeException {

    private final String line;

    public SdkMessageParseException(String message, String line, Throwable cause) {
        super(message, cause);
        this.line = line;
    }

    /** The offending raw line (may be truncated by callers before logging). */
    public String line() {
        return line;
    }
}
