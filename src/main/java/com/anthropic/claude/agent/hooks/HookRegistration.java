package com.anthropic.claude.agent.hooks;

/**
 * One hook registration: a callback bound to an event, optionally filtered by a {@code matcher}
 * (e.g. a tool-name pattern for PreToolUse) with an optional per-callback timeout (ms).
 *
 * @param event    the hook event
 * @param matcher  optional matcher (null = match all)
 * @param timeout  optional timeout in <b>seconds</b> for all hooks in this matcher (null = default)
 * @param callback the callback to invoke
 */
public record HookRegistration(HookEvent event, String matcher, Integer timeout, HookCallback callback) {
}
