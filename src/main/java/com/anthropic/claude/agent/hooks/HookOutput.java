package com.anthropic.claude.agent.hooks;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A hook callback's result (the {@code HookJSONOutput} sent back to the CLI). Either a synchronous
 * output (most fields) or the asynchronous variant ({@link #async}/{@link #asyncTimeout}). An
 * all-null instance serializes to {@code {}} (no-op / allow).
 *
 * @param continueExecution   {@code continue} — false halts the session
 * @param suppressOutput      hide the hook's stdout from the transcript
 * @param stopReason          reason shown when {@code continueExecution} is false
 * @param decision            "approve" or "block" (e.g. PreToolUse gating)
 * @param systemMessage       a system message to inject
 * @param reason              reason accompanying {@code decision}
 * @param terminalSequence    a terminal escape sequence to emit
 * @param hookSpecificOutput  event-specific output (e.g. PreToolUse permissionDecision/updatedInput)
 * @param async               when true, this is the async variant (CLI continues; result later)
 * @param asyncTimeout        async timeout (seconds)
 */
public record HookOutput(
        Boolean continueExecution,
        Boolean suppressOutput,
        String stopReason,
        String decision,
        String systemMessage,
        String reason,
        String terminalSequence,
        JsonNode hookSpecificOutput,
        Boolean async,
        Integer asyncTimeout) {

    /** No-op result (serializes to {@code {}}). */
    public static HookOutput cont() {
        return new HookOutput(null, null, null, null, null, null, null, null, null, null);
    }

    /** Block the action (decision = "block") with a reason. */
    public static HookOutput block(String reason) {
        return new HookOutput(null, null, null, "block", null, reason, null, null, null, null);
    }

    /** Approve the action (decision = "approve"). */
    public static HookOutput approve() {
        return new HookOutput(null, null, null, "approve", null, null, null, null, null, null);
    }

    /** Halt the session ({@code continue=false}) with a stop reason. */
    public static HookOutput stop(String stopReason) {
        return new HookOutput(false, null, stopReason, null, null, null, null, null, null, null);
    }

    /** Async variant: the CLI proceeds and the result is delivered later (timeout in seconds). */
    public static HookOutput async(int asyncTimeoutSeconds) {
        return new HookOutput(null, null, null, null, null, null, null, null, true, asyncTimeoutSeconds);
    }

    /** Attach event-specific output (e.g. PreToolUse updatedInput / additionalContext). */
    public HookOutput withHookSpecificOutput(JsonNode node) {
        return new HookOutput(continueExecution, suppressOutput, stopReason, decision,
                systemMessage, reason, terminalSequence, node, async, asyncTimeout);
    }
}
