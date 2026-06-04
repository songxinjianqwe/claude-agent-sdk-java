package com.anthropic.claude.agent.hooks;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A hook callback invoked when the CLI fires a registered hook ({@code hook_callback} control
 * request). Runs on a background thread.
 */
@FunctionalInterface
public interface HookCallback {

    /**
     * @param input     the hook input (a {@code HookInput} variant; common fields include
     *                  {@code session_id}, {@code cwd}, {@code hook_event_name}, plus event-specific
     *                  fields such as {@code tool_name}/{@code tool_input} for PreToolUse)
     * @param toolUseId the tool_use id, when the hook concerns a specific tool call (else null)
     * @return the result to return to the CLI (use {@link HookOutput#cont()} for a no-op)
     */
    HookOutput handle(JsonNode input, String toolUseId);
}
