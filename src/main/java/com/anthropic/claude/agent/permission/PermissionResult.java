package com.anthropic.claude.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The decision returned by a {@link CanUseTool} callback: allow the tool call (optionally rewriting
 * its input or persisting permission rules) or deny it (optionally interrupting the turn). Mirrors
 * the Node SDK {@code PermissionResult} union.
 */
public sealed interface PermissionResult permits PermissionResult.Allow, PermissionResult.Deny {

    /**
     * Allow the tool call.
     *
     * @param updatedInput          replacement tool input; when serialized, {@code null} is sent as
     *                              {@code {}} (the CLI treats an empty object as "use the original
     *                              input") — the protocol requires this field to be present
     * @param updatedPermissions    optional persistent permission rules (PermissionUpdate[])
     * @param decisionClassification optional: user_temporary / user_permanent / user_reject
     * @param toolUseId             optional override; defaults to the requested tool_use id
     */
    record Allow(JsonNode updatedInput, JsonNode updatedPermissions, String decisionClassification,
                 String toolUseId) implements PermissionResult {
    }

    /**
     * Deny the tool call.
     *
     * @param message               required human-readable reason
     * @param interrupt             when true, the CLI aborts the whole turn
     * @param decisionClassification optional classification
     * @param toolUseId             optional override; defaults to the requested tool_use id
     */
    record Deny(String message, boolean interrupt, String decisionClassification, String toolUseId)
            implements PermissionResult {
    }

    /** Allow with no input modification. */
    static Allow allow() {
        return new Allow(null, null, null, null);
    }

    /** Allow, replacing the tool input. */
    static Allow allow(JsonNode updatedInput) {
        return new Allow(updatedInput, null, null, null);
    }

    /** Deny with a message (no interrupt). */
    static Deny deny(String message) {
        return new Deny(message, false, null, null);
    }

    /** Deny with a message, optionally interrupting the turn. */
    static Deny deny(String message, boolean interrupt) {
        return new Deny(message, interrupt, null, null);
    }
}
