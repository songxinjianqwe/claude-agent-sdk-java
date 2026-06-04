/**
 * Claude Agent SDK for Java — a faithful port of {@code @anthropic-ai/claude-agent-sdk}.
 *
 * <p>Entry point: {@code ClaudeAgent.query(prompt, options)} returns a {@code Query}
 * (a {@link java.util.concurrent.Flow.Publisher} of {@code SdkMessage}) that drives the
 * {@code claude} CLI headless over the bidirectional stream-json protocol.
 *
 * <p>This package is the public surface; protocol/transport internals live under
 * {@code transport} and {@code internal}.
 */
package com.anthropic.claude.agent;
