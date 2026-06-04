package com.anthropic.claude.agent.internal;

import com.anthropic.claude.agent.ControlException;
import com.anthropic.claude.agent.InitializeResult;
import com.anthropic.claude.agent.McpServerStatus;
import com.anthropic.claude.agent.Options;
import com.anthropic.claude.agent.Query;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.hooks.HookRegistration;
import com.anthropic.claude.agent.mcp.SdkMcpServer;
import com.anthropic.claude.agent.message.SdkMessages;
import com.anthropic.claude.agent.permission.PermissionMode;
import com.anthropic.claude.agent.transport.Transport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Default {@link Query} implementation: wires a {@link Transport} to a {@link MessagePublisher},
 * routing each stdout line either to the control channel (control_request/control_response) or, for
 * everything else, parsing it into an {@link SdkMessage} and emitting it downstream.
 *
 * <p>Phase 2 only handles plain messages; control lines are logged and ignored (the control channel
 * is implemented in phase 4). A malformed (non-JSON) line is logged and skipped rather than killing
 * the stream.
 */
public final class QueryImpl implements Query, Transport.Listener {

    private static final Logger LOG = System.getLogger(QueryImpl.class.getName());

    private final Transport transport;
    private final Options options;
    private final ObjectMapper mapper = SdkMessages.mapper();
    private final MessagePublisher publisher;
    private final ControlProtocol control;
    private volatile boolean endInputAfterResult = false;
    private volatile boolean terminated = false;
    private CompletableFuture<JsonNode> initFuture;
    private int hookIdCounter = 0;

    public QueryImpl(Transport transport, Options options) {
        this.transport = transport;
        this.options = options;
        this.publisher = new MessagePublisher(options.bufferCapacity(), this::close);
        this.control = new ControlProtocol(transport, mapper, options.canUseTool(), options.onElicitation());
    }

    /**
     * Start the process and send a single string prompt. stdin is kept open until the {@code result}
     * arrives (so tool-permission control responses can flow during the turn), then closed.
     */
    public void startOneShot(String prompt) throws IOException {
        transport.start(this);
        maybeInitialize();
        transport.writeLine(buildUserMessage(prompt));
        endInputAfterResult = true;
    }

    /** Start the process without sending input; caller drives {@link #streamInput}/{@link #endInput}. */
    public void start() throws IOException {
        transport.start(this);
        maybeInitialize();
    }

    /**
     * Start the process and feed user turns from a reactive publisher: each emitted string becomes
     * a user message (in order); stdin is closed when the publisher completes.
     */
    public void startStreaming(Flow.Publisher<String> prompts) throws IOException {
        transport.start(this);
        maybeInitialize();
        prompts.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String content) {
                streamInput(content);
            }

            @Override
            public void onError(Throwable t) {
                LOG.log(Level.WARNING, "input publisher errored; closing stdin", t);
                transport.endInput();
            }

            @Override
            public void onComplete() {
                transport.endInput();
            }
        });
    }

    private String buildUserMessage(String prompt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "user");
        ObjectNode message = root.putObject("message");
        message.put("role", "user");
        message.put("content", prompt);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize user message", e);
        }
    }

    // --- Transport.Listener ----------------------------------------------------------------------

    @Override
    public void onLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, () -> "skipping non-JSON stream line: " + truncate(line));
            return;
        }
        if (node == null || !node.isObject()) {
            LOG.log(Level.WARNING, () -> "skipping non-object stream line: " + truncate(line));
            return;
        }
        String type = SdkMessage.text(node, "type");
        if ("control_request".equals(type) || "control_response".equals(type)
                || "control_cancel_request".equals(type)) {
            control.onControlLine(node);
            return;
        }
        if ("keep_alive".equals(type) || "update_environment_variables".equals(type)) {
            // Liveness / env frames — not user-facing messages; ignore silently (wire §C.3).
            return;
        }
        publisher.emit(SdkMessages.fromJson(node));
        // One-shot: the turn is done once the result arrives → close stdin so the CLI exits.
        if (endInputAfterResult && "result".equals(type)) {
            transport.endInput();
        }
    }

    @Override
    public void onExit(int code) {
        terminated = true;
        publisher.complete();
        control.rejectAllPending(new ControlException("claude process exited (code " + code + ")"));
    }

    @Override
    public void onError(Throwable t) {
        terminated = true;
        publisher.error(t);
        control.rejectAllPending(t);
    }

    // --- Query -----------------------------------------------------------------------------------

    @Override
    public void subscribe(Flow.Subscriber<? super SdkMessage> subscriber) {
        publisher.subscribe(subscriber);
    }

    @Override
    public void streamInput(String content) {
        transport.writeLine(buildUserMessage(content));
    }

    @Override
    public void streamInput(com.anthropic.claude.agent.UserMessageInput message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "user");
        ObjectNode msg = root.putObject("message");
        msg.put("role", "user");
        msg.set("content", message.content());
        if (message.parentToolUseId() != null) {
            root.put("parent_tool_use_id", message.parentToolUseId());
        }
        try {
            transport.writeLine(mapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize user message", e);
        }
    }

    @Override
    public void endInput() {
        transport.endInput();
    }

    // --- runtime control -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> interrupt() {
        return voidRequest("interrupt", null);
    }

    @Override
    public CompletableFuture<Void> setPermissionMode(PermissionMode mode) {
        return voidRequest("set_permission_mode", r -> r.put("mode", mode.wire()));
    }

    @Override
    public CompletableFuture<Void> setModel(String model) {
        return voidRequest("set_model", r -> {
            if (model != null) {
                r.put("model", model);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setMaxThinkingTokens(Integer maxThinkingTokens) {
        return voidRequest("set_max_thinking_tokens", r -> {
            if (maxThinkingTokens == null) {
                r.putNull("max_thinking_tokens");
            } else {
                r.put("max_thinking_tokens", maxThinkingTokens.intValue());
            }
        });
    }

    @Override
    public CompletableFuture<List<McpServerStatus>> mcpServerStatus() {
        if (terminated) {
            return CompletableFuture.failedFuture(new ControlException("query already ended"));
        }
        return control.sendRequest("mcp_status", null).thenApply(payload -> {
            List<McpServerStatus> out = new ArrayList<>();
            if (payload != null) {
                JsonNode arr = payload.get("mcpServers");
                if (arr != null && arr.isArray()) {
                    for (JsonNode n : arr) {
                        out.add(McpServerStatus.from(n));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<JsonNode> getContextUsage() {
        if (terminated) {
            return CompletableFuture.failedFuture(new ControlException("query already ended"));
        }
        return control.sendRequest("get_context_usage", null);
    }

    @Override
    public CompletableFuture<Void> reconnectMcpServer(String name) {
        return voidRequest("mcp_reconnect", r -> r.put("serverName", name));
    }

    @Override
    public CompletableFuture<Void> toggleMcpServer(String name, boolean enabled) {
        return voidRequest("mcp_toggle", r -> {
            r.put("serverName", name);
            r.put("enabled", enabled);
        });
    }

    @Override
    public CompletableFuture<Void> stopTask(String taskId) {
        return voidRequest("stop_task", r -> r.put("task_id", taskId));
    }

    @Override
    public CompletableFuture<Boolean> backgroundTasks(String toolUseId) {
        if (terminated) {
            return CompletableFuture.failedFuture(new ControlException("query already ended"));
        }
        return control.sendRequest("background_tasks", r -> {
            if (toolUseId != null) {
                r.put("tool_use_id", toolUseId);
            }
        }).thenApply(payload -> {
            // SDK: response.backgrounded ?? true
            if (payload == null) {
                return Boolean.TRUE;
            }
            JsonNode b = payload.get("backgrounded");
            return (b == null || b.isNull()) ? Boolean.TRUE : b.asBoolean();
        });
    }

    @Override
    public CompletableFuture<Void> applyFlagSettings(JsonNode settings) {
        return voidRequest("apply_flag_settings", r -> r.set("settings", settings));
    }

    @Override
    public CompletableFuture<JsonNode> setMcpServers(JsonNode servers) {
        if (terminated) {
            return CompletableFuture.failedFuture(new ControlException("query already ended"));
        }
        return control.sendRequest("mcp_set_servers", r -> r.set("servers", servers));
    }

    @Override
    public CompletableFuture<JsonNode> getSettings() {
        if (terminated) {
            return CompletableFuture.failedFuture(new ControlException("query already ended"));
        }
        return control.sendRequest("get_settings", null);
    }

    @Override
    public synchronized CompletableFuture<InitializeResult> initializationResult() {
        if (initFuture == null) {
            if (terminated) {
                return CompletableFuture.failedFuture(new ControlException("query already ended"));
            }
            initFuture = control.sendRequest("initialize", this::fillInitialize);
        }
        return initFuture.thenApply(payload ->
                new InitializeResult(payload != null ? payload : mapper.createObjectNode()));
    }

    /** Send the initialize handshake eagerly when there is something to register (hooks/MCP). */
    private synchronized void maybeInitialize() {
        if (initFuture == null && needsInitialize()) {
            initFuture = control.sendRequest("initialize", this::fillInitialize);
        }
    }

    private boolean needsInitialize() {
        return !options.hooks().isEmpty() || !options.sdkMcpServers().isEmpty();
    }

    /**
     * Populate the initialize request: build the {@code hooks} map (grouped by event) and register
     * each callback under a generated id so {@code hook_callback} requests can be dispatched.
     */
    private void fillInitialize(ObjectNode request) {
        if (!options.hooks().isEmpty()) {
            Map<String, ArrayNode> byEvent = new LinkedHashMap<>();
            for (HookRegistration reg : options.hooks()) {
                String id = "hook-" + (++hookIdCounter);
                control.registerHookCallback(id, reg.callback());
                ArrayNode matchers = byEvent.computeIfAbsent(reg.event().wire(), k -> mapper.createArrayNode());
                ObjectNode entry = matchers.addObject();
                if (reg.matcher() != null) {
                    entry.put("matcher", reg.matcher());
                }
                entry.putArray("hookCallbackIds").add(id);
                if (reg.timeout() != null) {
                    entry.put("timeout", reg.timeout().intValue());
                }
            }
            ObjectNode hooks = request.putObject("hooks");
            byEvent.forEach(hooks::set);
        }
        if (!options.sdkMcpServers().isEmpty()) {
            ArrayNode names = request.putArray("sdkMcpServers");
            for (SdkMcpServer server : options.sdkMcpServers()) {
                names.add(server.name());
                control.registerMcpServer(new InProcessMcpServer(server, mapper));
            }
        }
    }

    private CompletableFuture<Void> voidRequest(String subtype, Consumer<ObjectNode> fill) {
        if (terminated) {
            return CompletableFuture.failedFuture(new ControlException("query already ended"));
        }
        return control.sendRequest(subtype, fill).thenApply(ignored -> null);
    }

    @Override
    public void close() {
        transport.close();
        control.close();
        // Ensure any blocked subscriber is released if we are torn down before the stream ended.
        publisher.complete();
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
