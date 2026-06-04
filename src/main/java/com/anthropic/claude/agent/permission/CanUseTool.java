package com.anthropic.claude.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Host callback invoked when {@code claude} asks whether a tool may run (the {@code can_use_tool}
 * control request, sent only when the engine's permission decision is "ask"). Mirrors the Node SDK
 * {@code CanUseTool}.
 *
 * <p>Providing a callback makes the SDK launch claude with {@code --permission-prompt-tool stdio}
 * (and, unless overridden, {@code --permission-mode default}) so "ask" decisions are routed here.
 *
 * <p>The callback runs on a background thread; it may block (e.g. awaiting human approval) without
 * stalling the message stream.
 */
@FunctionalInterface
public interface CanUseTool {

    /**
     * @param toolName the tool being requested (e.g. "Bash")
     * @param input    the tool input arguments
     * @param context  additional context (tool_use id, suggestions, …)
     * @return an {@link PermissionResult.Allow} or {@link PermissionResult.Deny}
     */
    PermissionResult check(String toolName, JsonNode input, ToolPermissionContext context);
}
