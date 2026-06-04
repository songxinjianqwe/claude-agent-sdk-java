package com.anthropic.claude.agent;

/**
 * System-prompt configuration (the Node SDK {@code Options.systemPrompt} union). Either a raw string
 * that replaces the default prompt, or the {@code claude_code} preset (the default prompt) with an
 * optional append and dynamic-section exclusion.
 *
 * <p>CLI mapping:
 * <ul>
 *   <li>{@link Text} → {@code --system-prompt <text>}</li>
 *   <li>{@link Preset} → no {@code --system-prompt} (keep the default), plus
 *       {@code --append-system-prompt} and/or {@code --exclude-dynamic-system-prompt-sections}</li>
 * </ul>
 */
public sealed interface SystemPrompt permits SystemPrompt.Text, SystemPrompt.Preset {

    /** A raw system prompt that replaces the default. */
    record Text(String text) implements SystemPrompt {
    }

    /**
     * The {@code claude_code} preset (default prompt).
     *
     * @param append                 text appended to the default prompt (nullable)
     * @param excludeDynamicSections move per-machine sections out of the system prompt (cache reuse)
     */
    record Preset(String append, boolean excludeDynamicSections) implements SystemPrompt {
    }

    static Text text(String text) {
        return new Text(text);
    }

    /** The default preset with no modifications. */
    static Preset preset() {
        return new Preset(null, false);
    }

    /** The default preset with an appended prompt. */
    static Preset presetAppend(String append) {
        return new Preset(append, false);
    }

    static Preset preset(String append, boolean excludeDynamicSections) {
        return new Preset(append, excludeDynamicSections);
    }
}
