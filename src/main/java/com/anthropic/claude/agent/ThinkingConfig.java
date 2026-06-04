package com.anthropic.claude.agent;

/**
 * Extended-thinking configuration (the Node SDK {@code ThinkingConfig} union: adaptive / enabled
 * (+budgetTokens) / disabled). Preferred over the deprecated {@code Options.maxThinkingTokens}.
 *
 * <p>CLI mapping (matching the published SDK):
 * <ul>
 *   <li>{@code DISABLED} → {@code --thinking disabled}</li>
 *   <li>{@code ADAPTIVE} → {@code --thinking adaptive}</li>
 *   <li>{@code ENABLED} with budget → {@code --max-thinking-tokens <n>}</li>
 *   <li>{@code ENABLED} without budget → {@code --thinking adaptive}</li>
 * </ul>
 *
 * @param type         adaptive / enabled / disabled
 * @param budgetTokens fixed budget (only for {@link Type#ENABLED}), nullable
 * @param display      "summarized" or "omitted" (optional)
 */
public record ThinkingConfig(Type type, Integer budgetTokens, String display) {

    public enum Type {
        ADAPTIVE, ENABLED, DISABLED
    }

    public static ThinkingConfig adaptive() {
        return new ThinkingConfig(Type.ADAPTIVE, null, null);
    }

    public static ThinkingConfig enabled(int budgetTokens) {
        return new ThinkingConfig(Type.ENABLED, budgetTokens, null);
    }

    public static ThinkingConfig disabled() {
        return new ThinkingConfig(Type.DISABLED, null, null);
    }

    /** Set the thinking display ("summarized" or "omitted"); ignored when disabled. */
    public ThinkingConfig withDisplay(String display) {
        return new ThinkingConfig(type, budgetTokens, display);
    }
}
