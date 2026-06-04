package com.anthropic.claude.agent;

import com.anthropic.claude.agent.internal.BlockingSubscriber;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.permission.PermissionMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A running {@code claude} query — the Java analogue of the Node SDK's {@code Query}. It is a
 * {@link Flow.Publisher} of {@link SdkMessage} (one subscriber) and an {@link AutoCloseable} handle
 * to the underlying process.
 *
 * <p>Reactive consumption (with backpressure):
 * <pre>{@code
 * query.subscribe(new Flow.Subscriber<>() { ... });
 * }</pre>
 *
 * <p>Or blocking convenience (no backpressure — drains everything):
 * <pre>{@code
 * for (SdkMessage m : query.messages()) { ... }
 * List<SdkMessage> all = query.collect();
 * }</pre>
 *
 * <p>Runtime control methods (interrupt, setPermissionMode, …) are added in later phases.
 */
public interface Query extends Flow.Publisher<SdkMessage>, AutoCloseable {

    /** Terminate the underlying process and release resources. Idempotent. */
    @Override
    void close();

    /**
     * Send another user turn (streaming-input mode). Only meaningful for queries started via
     * {@link ClaudeAgent#streamingQuery} or the reactive input overload; for a one-shot query stdin
     * is already closed and this is a no-op (logged).
     */
    void streamInput(String content);

    /** Send a structured user turn (content blocks / parent_tool_use_id) in streaming-input mode. */
    void streamInput(UserMessageInput message);

    /** Signal that no further input will be sent (closes stdin). */
    void endInput();

    // --- runtime control (over the control protocol) ---------------------------------------------

    /** Abort the current turn ({@code interrupt} control request). */
    CompletableFuture<Void> interrupt();

    /** Change the permission mode for subsequent tool use. */
    CompletableFuture<Void> setPermissionMode(PermissionMode mode);

    /** Switch the main model; {@code null} (or "default") restores the default model. */
    CompletableFuture<Void> setModel(String model);

    /**
     * Set the thinking-token budget: {@code null} clears the config, {@code 0} disables thinking,
     * a positive value enables it with that budget.
     */
    CompletableFuture<Void> setMaxThinkingTokens(Integer maxThinkingTokens);

    /** Query the status of configured MCP servers ({@code mcp_status}). */
    CompletableFuture<List<McpServerStatus>> mcpServerStatus();

    /** Query current context-window usage ({@code get_context_usage}); returns the raw payload. */
    CompletableFuture<JsonNode> getContextUsage();

    /** Reconnect a configured MCP server by name ({@code mcp_reconnect}). */
    CompletableFuture<Void> reconnectMcpServer(String name);

    /** Enable/disable a configured MCP server ({@code mcp_toggle}). */
    CompletableFuture<Void> toggleMcpServer(String name, boolean enabled);

    /** Stop a background task by id ({@code stop_task}). */
    CompletableFuture<Void> stopTask(String taskId);

    /**
     * Background a running task ({@code background_tasks}, Ctrl+B semantics): with a {@code toolUseId}
     * only that task; null backgrounds all foreground tasks. Returns whether anything was backgrounded
     * (defaults to true if the CLI omits the flag). Requires a CLI version that supports
     * {@code background_tasks}; older CLIs reject it (future completes with {@link ControlException}).
     */
    CompletableFuture<Boolean> backgroundTasks(String toolUseId);

    /** Background all foreground tasks. */
    default CompletableFuture<Boolean> backgroundTasks() {
        return backgroundTasks(null);
    }

    /** Apply (shallow-merge) flag settings mid-session; null values delete keys ({@code apply_flag_settings}). */
    CompletableFuture<Void> applyFlagSettings(JsonNode settings);

    /** Replace the set of MCP servers ({@code mcp_set_servers}); returns the raw {added,removed,errors}. */
    CompletableFuture<JsonNode> setMcpServers(JsonNode servers);

    /** Get effective settings and their sources ({@code get_settings}); returns the raw payload. */
    CompletableFuture<JsonNode> getSettings();

    /** The {@code initialize} handshake result (sent lazily on first call, then cached). */
    CompletableFuture<InitializeResult> initializationResult();

    /** Models advertised by the CLI (derived from {@link #initializationResult()}). */
    default CompletableFuture<List<InitializeResult.ModelInfo>> supportedModels() {
        return initializationResult().thenApply(InitializeResult::models);
    }

    /** Slash commands advertised by the CLI. */
    default CompletableFuture<List<InitializeResult.SlashCommand>> supportedCommands() {
        return initializationResult().thenApply(InitializeResult::commands);
    }

    /** Agents advertised by the CLI. */
    default CompletableFuture<List<InitializeResult.AgentInfo>> supportedAgents() {
        return initializationResult().thenApply(InitializeResult::agents);
    }

    /** Account info advertised by the CLI. */
    default CompletableFuture<InitializeResult.AccountInfo> accountInfo() {
        return initializationResult().thenApply(InitializeResult::account);
    }

    /** A blocking {@link Iterable} of messages (subscribes once; do not also call {@link #subscribe}). */
    default Iterable<SdkMessage> messages() {
        BlockingSubscriber sub = new BlockingSubscriber();
        subscribe(sub);
        return sub::iterator;
    }

    /** A blocking {@link Stream} of messages. */
    default Stream<SdkMessage> stream() {
        Iterator<SdkMessage> it = messages().iterator();
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /** Block until the query finishes and collect every message (convenience for one-shot use). */
    default List<SdkMessage> collect() {
        return stream().toList();
    }

    /**
     * Block until the query finishes and collect every message, with a hard wall-clock bound. On
     * timeout the underlying process is {@link #close() closed} and {@link QueryTimeoutException} is
     * thrown; a {@code claude} that hangs can therefore never wedge the caller forever. If the
     * message stream itself errors, that error is propagated (unwrapped if it is unchecked).
     *
     * @param timeout max time to wait; must be positive
     */
    default List<SdkMessage> collect(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("timeout must be > 0, was " + timeout);
        }
        AtomicReference<List<SdkMessage>> out = new AtomicReference<>();
        Throwable[] err = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                out.set(collect());
            } catch (Throwable t) {
                err[0] = t;
            }
        }, "claude-collect-timeout");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new QueryTimeoutException("interrupted while awaiting query completion");
        }
        if (worker.isAlive()) {
            close();              // abort the hung process; the worker's blocking take() then unblocks
            worker.interrupt();
            throw new QueryTimeoutException("query did not complete within " + timeout);
        }
        if (err[0] != null) {
            if (err[0] instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(err[0]);
        }
        return out.get();
    }
}
