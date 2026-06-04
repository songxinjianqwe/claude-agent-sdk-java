package com.anthropic.claude.agent.internal;

import com.anthropic.claude.agent.ControlException;
import com.anthropic.claude.agent.elicitation.ElicitationRequest;
import com.anthropic.claude.agent.elicitation.ElicitationResult;
import com.anthropic.claude.agent.elicitation.OnElicitation;
import com.anthropic.claude.agent.hooks.HookCallback;
import com.anthropic.claude.agent.hooks.HookOutput;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.permission.CanUseTool;
import com.anthropic.claude.agent.permission.PermissionResult;
import com.anthropic.claude.agent.permission.ToolPermissionContext;
import com.anthropic.claude.agent.transport.Transport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Bidirectional control protocol over the {@link Transport}, multiplexed onto the same NDJSON stdio
 * as the message stream.
 *
 * <p>Envelopes (wire-protocol.md §C.1 / §E.7) — note the asymmetric nesting:
 * <pre>
 * control_request : {"type":"control_request","request_id":id,"request":{"subtype":..,..}}
 * control_response: {"type":"control_response","response":{"subtype":"success"|"error",
 *                                                           "request_id":id,"response":{..}|"error":..}}
 * </pre>
 *
 * <ul>
 *   <li><b>SDK → CLI</b> ({@link #sendRequest}): interrupt / set_permission_mode / set_model / … —
 *       returns a future of the success payload ({@code response.response}).</li>
 *   <li><b>CLI → SDK</b> ({@link #handleRequest}): {@code can_use_tool} (and, in later phases,
 *       {@code hook_callback} / {@code mcp_message}) — invoked on a background thread so a slow host
 *       callback never stalls the reader; the response is written back when it completes.</li>
 * </ul>
 *
 * <p>Robustness: request_id matching tolerates a camelCase {@code requestId} on read (§E.8);
 * duplicate inbound requests are de-duplicated (§E.12); stdin EOF rejects all pending requests
 * (§E.13).
 */
public final class ControlProtocol {

    private static final Logger LOG = System.getLogger(ControlProtocol.class.getName());

    private final Transport transport;
    private final ObjectMapper mapper;
    private final CanUseTool canUseTool;
    private final OnElicitation onElicitation;
    private final ExecutorService callbackExecutor;

    /** Cap on remembered inbound request ids (matches the CLI's resolvedToolUseIds cap, wire §E.12). */
    private static final int MAX_INBOUND_IDS = 1000;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    // Bounded LRU dedup set: oldest ids are evicted past MAX_INBOUND_IDS so a long-running query
    // does not leak memory accumulating every historical request id.
    private final Map<String, Boolean> handledInboundIds = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_INBOUND_IDS;
                }
            });
    private final Map<String, HookCallback> hookCallbacks = new ConcurrentHashMap<>();
    private final Map<String, InProcessMcpServer> mcpServers = new ConcurrentHashMap<>();

    public ControlProtocol(Transport transport, ObjectMapper mapper, CanUseTool canUseTool) {
        this(transport, mapper, canUseTool, null);
    }

    public ControlProtocol(Transport transport, ObjectMapper mapper, CanUseTool canUseTool,
                           OnElicitation onElicitation) {
        this.transport = transport;
        this.mapper = mapper;
        this.canUseTool = canUseTool;
        this.onElicitation = onElicitation;
        this.callbackExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "claude-sdk-control-callback");
            t.setDaemon(true);
            return t;
        });
    }

    // --- SDK → CLI ------------------------------------------------------------------------------

    /**
     * Send a control_request and return a future of its success payload ({@code response.response},
     * which may be a JSON null node for empty-payload responses). The future completes exceptionally
     * with {@link ControlException} on an error response or channel close.
     *
     * @param subtype     the request subtype (e.g. "interrupt")
     * @param fillRequest optional: populate subtype-specific fields on the inner {@code request} node
     */
    public CompletableFuture<JsonNode> sendRequest(String subtype, Consumer<ObjectNode> fillRequest) {
        String id = UUID.randomUUID().toString();
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "control_request");
        root.put("request_id", id);
        ObjectNode request = root.putObject("request");
        request.put("subtype", subtype);
        if (fillRequest != null) {
            fillRequest.accept(request);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            transport.writeLine(mapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            pending.remove(id);
            future.completeExceptionally(new ControlException("failed to serialize control_request", e));
        }
        return future;
    }

    // --- routing --------------------------------------------------------------------------------

    /** Route an inbound control line (called from the transport reader thread). */
    public void onControlLine(JsonNode node) {
        String type = SdkMessage.text(node, "type");
        switch (type) {
            case "control_response" -> handleResponse(node);
            case "control_request" -> handleRequest(node);
            case "control_cancel_request" -> LOG.log(Level.DEBUG,
                    () -> "received control_cancel_request (not yet acted upon)");
            default -> LOG.log(Level.WARNING, () -> "unexpected control line type: " + type);
        }
    }

    private void handleResponse(JsonNode node) {
        JsonNode response = node.get("response");
        if (response == null) {
            LOG.log(Level.WARNING, "control_response missing 'response' object");
            return;
        }
        String requestId = requestId(response);
        if (requestId == null) {
            LOG.log(Level.WARNING, "control_response missing request_id");
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(requestId);
        if (future == null) {
            // Unknown or duplicate response (§E.12) — ignore idempotently.
            LOG.log(Level.DEBUG, () -> "no pending request for control_response id=" + requestId);
            return;
        }
        String subtype = SdkMessage.text(response, "subtype");
        if ("error".equals(subtype)) {
            String error = SdkMessage.text(response, "error");
            future.completeExceptionally(new ControlException(
                    "control request failed: " + (error == null ? "(no message)" : error)));
        } else {
            future.complete(response.get("response")); // success payload (may be null)
        }
    }

    private void handleRequest(JsonNode node) {
        String requestId = SdkMessage.text(node, "request_id");
        if (requestId == null) {
            requestId = SdkMessage.text(node, "requestId"); // §E.8 camelCase tolerance
        }
        if (requestId == null) {
            LOG.log(Level.WARNING, "inbound control_request missing request_id");
            return;
        }
        if (handledInboundIds.putIfAbsent(requestId, Boolean.TRUE) != null) {
            return; // duplicate inbound request — ignore (§E.12)
        }
        JsonNode request = node.get("request");
        String subtype = SdkMessage.text(request, "subtype");
        if (subtype == null) {
            LOG.log(Level.WARNING, "inbound control_request missing subtype");
            return;
        }
        final String id = requestId;
        switch (subtype) {
            case "can_use_tool" -> handleCanUseTool(id, request);
            case "hook_callback" -> handleHookCallback(id, request);
            case "mcp_message" -> handleMcpMessage(id, request);
            case "elicitation" -> handleElicitation(id, request);
            default -> {
                // Reply with an error so the CLI does not hang waiting (unknown subtype must not crash us).
                LOG.log(Level.WARNING, () -> "unsupported inbound control subtype: " + subtype);
                sendErrorResponse(id, "unsupported control subtype: " + subtype);
            }
        }
    }

    /** Register an in-process MCP server under its name for {@code mcp_message} dispatch. */
    public void registerMcpServer(InProcessMcpServer server) {
        mcpServers.put(server.name(), server);
    }

    private void handleMcpMessage(String requestId, JsonNode request) {
        String serverName = SdkMessage.text(request, "server_name");
        JsonNode message = request.get("message");
        InProcessMcpServer server = serverName == null ? null : mcpServers.get(serverName);
        if (server == null) {
            sendErrorResponse(requestId, "unknown in-process MCP server: " + serverName);
            return;
        }
        callbackExecutor.execute(() -> {
            JsonNode rpcResponse = server.handle(message);
            ObjectNode payload = mapper.createObjectNode();
            if (rpcResponse != null) {
                // CLI expects response.response = { mcp_response: <JSON-RPC message> } (wire §C.3).
                payload.set("mcp_response", rpcResponse);
            }
            sendSuccessResponse(requestId, payload);
        });
    }

    private void handleElicitation(String requestId, JsonNode request) {
        if (onElicitation == null) {
            sendSuccessResponse(requestId, mapper.createObjectNode().put("action", "cancel"));
            return;
        }
        ElicitationRequest req = new ElicitationRequest(
                SdkMessage.text(request, "mcp_server_name"),
                SdkMessage.text(request, "message"),
                SdkMessage.text(request, "mode"),
                SdkMessage.text(request, "url"),
                SdkMessage.text(request, "elicitation_id"),
                request.get("requested_schema"));
        callbackExecutor.execute(() -> {
            ElicitationResult result;
            try {
                result = onElicitation.handle(req);
                if (result == null) {
                    result = ElicitationResult.cancel();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "onElicitation threw; cancelling", e);
                result = ElicitationResult.cancel();
            }
            ObjectNode payload = mapper.createObjectNode();
            payload.put("action", result.action());
            if (result.content() != null) {
                payload.set("content", result.content());
            }
            sendSuccessResponse(requestId, payload);
        });
    }

    /** Register an SDK hook callback under the id advertised in the initialize handshake. */
    public void registerHookCallback(String callbackId, HookCallback callback) {
        hookCallbacks.put(callbackId, callback);
    }

    private void handleHookCallback(String requestId, JsonNode request) {
        String callbackId = SdkMessage.text(request, "callback_id");
        HookCallback callback = callbackId == null ? null : hookCallbacks.get(callbackId);
        if (callback == null) {
            LOG.log(Level.WARNING, () -> "no hook callback for id=" + callbackId);
            sendSuccessResponse(requestId, mapper.createObjectNode());
            return;
        }
        JsonNode input = request.get("input");
        String toolUseId = SdkMessage.text(request, "tool_use_id");
        callbackExecutor.execute(() -> {
            HookOutput out;
            try {
                out = callback.handle(input, toolUseId);
                if (out == null) {
                    out = HookOutput.cont();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "hook callback threw; returning empty output", e);
                out = HookOutput.cont();
            }
            sendSuccessResponse(requestId, hookOutputToJson(out));
        });
    }

    private JsonNode hookOutputToJson(HookOutput o) {
        ObjectNode n = mapper.createObjectNode();
        if (Boolean.TRUE.equals(o.async())) {
            // Async variant is a distinct union member: {async:true, asyncTimeout?}
            n.put("async", true);
            if (o.asyncTimeout() != null) {
                n.put("asyncTimeout", o.asyncTimeout().intValue());
            }
            return n;
        }
        if (o.continueExecution() != null) {
            n.put("continue", o.continueExecution());
        }
        if (o.suppressOutput() != null) {
            n.put("suppressOutput", o.suppressOutput());
        }
        if (o.stopReason() != null) {
            n.put("stopReason", o.stopReason());
        }
        if (o.decision() != null) {
            n.put("decision", o.decision());
        }
        if (o.systemMessage() != null) {
            n.put("systemMessage", o.systemMessage());
        }
        if (o.reason() != null) {
            n.put("reason", o.reason());
        }
        if (o.terminalSequence() != null) {
            n.put("terminalSequence", o.terminalSequence());
        }
        if (o.hookSpecificOutput() != null) {
            n.set("hookSpecificOutput", o.hookSpecificOutput());
        }
        return n;
    }

    private void handleCanUseTool(String requestId, JsonNode request) {
        if (canUseTool == null) {
            // Should not happen (the flag is only set when a callback exists), but be safe.
            sendErrorResponse(requestId, "no canUseTool handler registered");
            return;
        }
        String toolName = SdkMessage.text(request, "tool_name");
        JsonNode input = request.get("input");
        ToolPermissionContext ctx = new ToolPermissionContext(
                SdkMessage.text(request, "tool_use_id"),
                SdkMessage.text(request, "agent_id"),
                request.get("permission_suggestions"),
                SdkMessage.text(request, "blocked_path"),
                SdkMessage.text(request, "decision_reason"),
                SdkMessage.text(request, "title"),
                SdkMessage.text(request, "display_name"),
                SdkMessage.text(request, "description"));

        callbackExecutor.execute(() -> {
            PermissionResult result;
            try {
                result = canUseTool.check(toolName, input, ctx);
                if (result == null) {
                    result = PermissionResult.deny("canUseTool returned null");
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "canUseTool callback threw; denying tool", e);
                result = PermissionResult.deny("canUseTool callback failed: " + e);
            }
            sendCanUseToolResponse(requestId, ctx.toolUseId(), result);
        });
    }

    private void sendCanUseToolResponse(String requestId, String toolUseId, PermissionResult result) {
        ObjectNode payload = mapper.createObjectNode();
        String effectiveToolUseId = toolUseId;
        if (result instanceof PermissionResult.Allow allow) {
            payload.put("behavior", "allow");
            // updatedInput is required by the CLI; send {} when there is no modification (§ can_use_tool).
            payload.set("updatedInput",
                    allow.updatedInput() != null ? allow.updatedInput() : mapper.createObjectNode());
            if (allow.updatedPermissions() != null) {
                payload.set("updatedPermissions", allow.updatedPermissions());
            }
            if (allow.decisionClassification() != null) {
                payload.put("decisionClassification", allow.decisionClassification());
            }
            if (allow.toolUseId() != null) {
                effectiveToolUseId = allow.toolUseId();
            }
        } else if (result instanceof PermissionResult.Deny deny) {
            payload.put("behavior", "deny");
            payload.put("message", deny.message());
            payload.put("interrupt", deny.interrupt());
            if (deny.decisionClassification() != null) {
                payload.put("decisionClassification", deny.decisionClassification());
            }
            if (deny.toolUseId() != null) {
                effectiveToolUseId = deny.toolUseId();
            }
        }
        if (effectiveToolUseId != null) {
            payload.put("toolUseID", effectiveToolUseId); // NOTE: camelCase in the response (§E.9)
        }
        sendSuccessResponse(requestId, payload);
    }

    private void sendSuccessResponse(String requestId, JsonNode payload) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "control_response");
        ObjectNode response = root.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        response.set("response", payload);
        writeControl(root);
    }

    private void sendErrorResponse(String requestId, String message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "control_response");
        ObjectNode response = root.putObject("response");
        response.put("subtype", "error");
        response.put("request_id", requestId);
        response.put("error", message);
        writeControl(root);
    }

    private void writeControl(ObjectNode root) {
        try {
            transport.writeLine(mapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            LOG.log(Level.ERROR, "failed to serialize control message", e);
        }
    }

    private static String requestId(JsonNode response) {
        String id = SdkMessage.text(response, "request_id");
        return id != null ? id : SdkMessage.text(response, "requestId"); // §E.8
    }

    /** Reject all in-flight SDK→CLI requests (called on stdin EOF / transport error, §E.13). */
    public void rejectAllPending(Throwable cause) {
        ControlException ex = cause instanceof ControlException ce
                ? ce
                : new ControlException("control channel closed before response", cause);
        pending.forEach((id, future) -> future.completeExceptionally(ex));
        pending.clear();
    }

    /** Shut down the callback executor and reject any remaining requests. */
    public void close() {
        callbackExecutor.shutdownNow();
        rejectAllPending(new ControlException("query closed"));
    }
}
