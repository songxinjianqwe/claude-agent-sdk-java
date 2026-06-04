package com.anthropic.claude.agent;

import java.time.Duration;

/**
 * Thrown by {@link Query#collect(Duration)} when the query does not finish within the given timeout.
 * The underlying process has already been {@link Query#close() closed} when this is thrown.
 */
public class QueryTimeoutException extends RuntimeException {

    public QueryTimeoutException(String message) {
        super(message);
    }
}
