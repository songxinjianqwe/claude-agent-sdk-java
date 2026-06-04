package com.anthropic.claude.agent.permission;

/**
 * Permission mode for a session — the values accepted by the CLI's {@code --permission-mode} flag
 * and the {@code set_permission_mode} control request.
 */
public enum PermissionMode {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    BYPASS_PERMISSIONS("bypassPermissions"),
    PLAN("plan"),
    DONT_ASK("dontAsk"),
    AUTO("auto");

    private final String wire;

    PermissionMode(String wire) {
        this.wire = wire;
    }

    /** The on-the-wire string (e.g. "acceptEdits"). */
    public String wire() {
        return wire;
    }

    /** Map a wire string to a {@link PermissionMode}. */
    public static PermissionMode fromWire(String s) {
        for (PermissionMode m : values()) {
            if (m.wire.equals(s)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown permission mode: " + s);
    }
}
