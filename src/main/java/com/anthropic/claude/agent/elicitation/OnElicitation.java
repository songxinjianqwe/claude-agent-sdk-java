package com.anthropic.claude.agent.elicitation;

/**
 * Host callback for MCP elicitation requests (the {@code elicitation} control request). Runs on a
 * background thread; returning null or throwing defaults to {@link ElicitationResult#cancel()}.
 */
@FunctionalInterface
public interface OnElicitation {
    ElicitationResult handle(ElicitationRequest request);
}
