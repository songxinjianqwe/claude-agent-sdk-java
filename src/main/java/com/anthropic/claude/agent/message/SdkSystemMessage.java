package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: "system"} — a control/lifecycle event from the CLI. The CLI emits ~16 distinct
 * {@code subtype}s (init, status, compact_boundary, hook_started, hook_response, post_turn_summary,
 * api_retry, task_*, session_state_changed, files_persisted, ...). Only {@code init} is given a
 * typed view ({@link #init()}); all are accessible through {@link #raw()}.
 *
 * @param subtype   the system subtype (e.g. "init", "status")
 * @param sessionId session id
 * @param uuid      message uuid
 * @param raw       original JSON
 */
public record SdkSystemMessage(
        String subtype,
        String sessionId,
        String uuid,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "system";
    }

    /** True if this is the initialization system message ({@code subtype == "init"}). */
    public boolean isInit() {
        return "init".equals(subtype);
    }

    /**
     * The typed initialization payload if {@link #isInit()}, else null. Parsed lazily from
     * {@link #raw()}.
     */
    public Init init() {
        if (!isInit()) {
            return null;
        }
        return Init.from(raw());
    }

    /** Status of a configured MCP server, as reported in the init message. */
    public record McpServerStatus(String name, String status) {
    }

    /**
     * Typed view of the {@code subtype:"init"} system message. Unmodeled fields remain in
     * {@link SdkSystemMessage#raw()}.
     */
    public record Init(
            String apiKeySource,
            String cwd,
            String sessionId,
            String model,
            String permissionMode,
            String outputStyle,
            String claudeCodeVersion,
            List<String> tools,
            List<String> slashCommands,
            List<String> agents,
            List<McpServerStatus> mcpServers) {

        static Init from(JsonNode n) {
            return new Init(
                    SdkMessage.text(n, "apiKeySource"),
                    SdkMessage.text(n, "cwd"),
                    SdkMessage.text(n, "session_id"),
                    SdkMessage.text(n, "model"),
                    SdkMessage.text(n, "permissionMode"),
                    SdkMessage.text(n, "output_style"),
                    SdkMessage.text(n, "claude_code_version"),
                    strings(n.get("tools")),
                    strings(n.get("slash_commands")),
                    strings(n.get("agents")),
                    servers(n.get("mcp_servers")));
        }

        private static List<String> strings(JsonNode arr) {
            List<String> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode e : arr) {
                    out.add(e.asText());
                }
            }
            return out;
        }

        private static List<McpServerStatus> servers(JsonNode arr) {
            List<McpServerStatus> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode e : arr) {
                    out.add(new McpServerStatus(
                            SdkMessage.text(e, "name"), SdkMessage.text(e, "status")));
                }
            }
            return out;
        }
    }
}
