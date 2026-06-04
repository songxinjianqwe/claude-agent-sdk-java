package com.anthropic.claude.agent.hooks;

/**
 * Hook lifecycle events the CLI can call back on (the {@code HOOK_EVENTS} enum). Registered via
 * {@code Options.builder().hook(...)} and delivered as {@code hook_callback} control requests.
 */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),
    POST_TOOL_BATCH("PostToolBatch"),
    NOTIFICATION("Notification"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    USER_PROMPT_EXPANSION("UserPromptExpansion"),
    SESSION_START("SessionStart"),
    SESSION_END("SessionEnd"),
    STOP("Stop"),
    STOP_FAILURE("StopFailure"),
    SUBAGENT_START("SubagentStart"),
    SUBAGENT_STOP("SubagentStop"),
    PRE_COMPACT("PreCompact"),
    POST_COMPACT("PostCompact"),
    PERMISSION_REQUEST("PermissionRequest"),
    PERMISSION_DENIED("PermissionDenied"),
    SETUP("Setup"),
    TEAMMATE_IDLE("TeammateIdle"),
    TASK_CREATED("TaskCreated"),
    TASK_COMPLETED("TaskCompleted"),
    ELICITATION("Elicitation"),
    ELICITATION_RESULT("ElicitationResult"),
    CONFIG_CHANGE("ConfigChange"),
    WORKTREE_CREATE("WorktreeCreate"),
    WORKTREE_REMOVE("WorktreeRemove"),
    INSTRUCTIONS_LOADED("InstructionsLoaded"),
    CWD_CHANGED("CwdChanged"),
    FILE_CHANGED("FileChanged"),
    MESSAGE_DISPLAY("MessageDisplay");

    private final String wire;

    HookEvent(String wire) {
        this.wire = wire;
    }

    /** The on-the-wire event name (e.g. "PreToolUse"). */
    public String wire() {
        return wire;
    }

    /** Map a wire name to a {@link HookEvent}. */
    public static HookEvent fromWire(String s) {
        for (HookEvent e : values()) {
            if (e.wire.equals(s)) {
                return e;
            }
        }
        throw new IllegalArgumentException("unknown hook event: " + s);
    }
}
