package com.anthropic.claude.agent;

/**
 * A session plugin config (the Node SDK {@code SdkPluginConfig}). Currently only {@code local} is
 * supported. Maps to {@code --plugin-dir <path>}.
 *
 * @param type plugin type (only "local")
 * @param path absolute or relative path to the plugin directory
 */
public record PluginConfig(String type, String path) {

    public static PluginConfig local(String path) {
        return new PluginConfig("local", path);
    }
}
