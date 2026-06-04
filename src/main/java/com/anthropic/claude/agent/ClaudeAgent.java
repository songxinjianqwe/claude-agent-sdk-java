package com.anthropic.claude.agent;

import com.anthropic.claude.agent.internal.QueryImpl;
import com.anthropic.claude.agent.mcp.SdkMcpServer;
import com.anthropic.claude.agent.mcp.SdkMcpTool;
import com.anthropic.claude.agent.mcp.ToolHandler;
import com.anthropic.claude.agent.transport.ProcessTransport;
import com.anthropic.claude.agent.transport.Transport;
import com.anthropic.claude.agent.transport.TransportException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Entry point of the Claude Agent SDK — the Java analogue of the Node SDK's {@code query()}.
 *
 * <pre>{@code
 * Query q = ClaudeAgent.query("Summarize README.md", Options.builder()
 *         .model("claude-opus-4-7")
 *         .cwd("/path/to/project")
 *         .build());
 * for (SdkMessage m : q.messages()) {
 *     if (m instanceof SdkResultMessage r) System.out.println(r.result());
 * }
 * }</pre>
 */
public final class ClaudeAgent {

    private ClaudeAgent() {
    }

    /** Run a one-shot string prompt with default options. */
    public static Query query(String prompt) {
        return query(prompt, Options.defaults());
    }

    /**
     * Run a one-shot string prompt: spawn {@code claude}, send the prompt as a single stream-json
     * user message, close stdin, and stream back the resulting messages.
     *
     * @throws TransportException if the process cannot be started
     */
    public static Query query(String prompt, Options options) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        Options opts = options == null ? Options.defaults() : options;
        Transport transport = new ProcessTransport(opts);
        return query(prompt, opts, transport);
    }

    /**
     * Run a one-shot prompt over an explicit {@link Transport}. Primarily for testing (inject an
     * in-memory transport); production callers use the {@link Options} overload.
     */
    public static Query query(String prompt, Options options, Transport transport) {
        QueryImpl query = new QueryImpl(transport, options);
        try {
            query.startOneShot(prompt);
        } catch (IOException e) {
            transport.close();
            throw new TransportException("failed to start claude process", e);
        }
        return query;
    }

    /**
     * Streaming-input mode: spawn {@code claude} with stdin left open. The caller sends turns via
     * {@link Query#streamInput} and finishes with {@link Query#endInput}.
     */
    public static Query streamingQuery(Options options) {
        Options opts = options == null ? Options.defaults() : options;
        return streamingQuery(opts, new ProcessTransport(opts));
    }

    /** Streaming-input mode over an explicit transport (testing seam). */
    public static Query streamingQuery(Options options, Transport transport) {
        QueryImpl query = new QueryImpl(transport, options);
        try {
            query.start();
        } catch (IOException e) {
            transport.close();
            throw new TransportException("failed to start claude process", e);
        }
        return query;
    }

    /**
     * Reactive multi-turn: feed user turns from {@code prompts} (each string becomes a user message,
     * in order); stdin closes when {@code prompts} completes.
     */
    public static Query query(Flow.Publisher<String> prompts, Options options) {
        Options opts = options == null ? Options.defaults() : options;
        return query(prompts, opts, new ProcessTransport(opts));
    }

    /** Reactive multi-turn over an explicit transport (testing seam). */
    public static Query query(Flow.Publisher<String> prompts, Options options, Transport transport) {
        QueryImpl query = new QueryImpl(transport, options);
        try {
            query.startStreaming(prompts);
        } catch (IOException e) {
            transport.close();
            throw new TransportException("failed to start claude process", e);
        }
        return query;
    }

    // --- in-process MCP factories (mirror the Node top-level tool() / createSdkMcpServer()) -------

    /** Define an in-process MCP tool. */
    public static SdkMcpTool tool(String name, String description, JsonNode inputSchema, ToolHandler handler) {
        return SdkMcpTool.of(name, description, inputSchema, handler);
    }

    /** Create an in-process MCP server from a list of tools. */
    public static SdkMcpServer createSdkMcpServer(String name, String version, List<SdkMcpTool> tools) {
        return SdkMcpServer.create(name, version, tools);
    }

    /** Create an in-process MCP server (version "1.0.0") from tools. */
    public static SdkMcpServer createSdkMcpServer(String name, SdkMcpTool... tools) {
        return SdkMcpServer.create(name, tools);
    }
}
