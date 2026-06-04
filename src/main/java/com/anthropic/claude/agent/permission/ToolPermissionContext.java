package com.anthropic.claude.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Context passed to a {@link CanUseTool} callback when {@code claude} asks the host to approve a
 * tool call (the {@code can_use_tool} control request). Mirrors the Node SDK's
 * {@code ToolPermissionContext}.
 *
 * @param toolUseId      the tool_use id this request concerns (required by the protocol)
 * @param agentId        the subagent id, if the call originates from a subagent (else null)
 * @param suggestions    raw {@code permission_suggestions} (PermissionUpdate[]) the CLI proposes
 * @param blockedPath    a path that triggered the prompt (e.g. outside cwd), if any
 * @param decisionReason serialized reason the engine decided to ask, if any
 * @param title          a short title for the prompt, if provided
 * @param displayName    a display name for the tool, if provided
 * @param description    a longer description, if provided
 */
public record ToolPermissionContext(
        String toolUseId,
        String agentId,
        JsonNode suggestions,
        String blockedPath,
        String decisionReason,
        String title,
        String displayName,
        String description) {
}
