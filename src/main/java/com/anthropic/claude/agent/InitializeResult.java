package com.anthropic.claude.agent;

import com.anthropic.claude.agent.message.SdkMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of the {@code initialize} control handshake (the success payload of the initialize
 * control response). Exposes the CLI's advertised commands, agents, models, account and output
 * styles. Fields are parsed lazily from {@link #raw()}.
 */
public record InitializeResult(JsonNode raw) {

    public List<ModelInfo> models() {
        List<ModelInfo> out = new ArrayList<>();
        JsonNode arr = raw.get("models");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new ModelInfo(SdkMessage.text(n, "value"), SdkMessage.text(n, "displayName"),
                        SdkMessage.text(n, "description"), n));
            }
        }
        return out;
    }

    public List<SlashCommand> commands() {
        List<SlashCommand> out = new ArrayList<>();
        JsonNode arr = raw.get("commands");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new SlashCommand(SdkMessage.text(n, "name"), SdkMessage.text(n, "description"),
                        SdkMessage.text(n, "argumentHint"), n));
            }
        }
        return out;
    }

    public List<AgentInfo> agents() {
        List<AgentInfo> out = new ArrayList<>();
        JsonNode arr = raw.get("agents");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new AgentInfo(SdkMessage.text(n, "name"), SdkMessage.text(n, "description"),
                        SdkMessage.text(n, "model"), n));
            }
        }
        return out;
    }

    public AccountInfo account() {
        JsonNode n = raw.get("account");
        if (n == null || !n.isObject()) {
            return null;
        }
        return new AccountInfo(SdkMessage.text(n, "email"), SdkMessage.text(n, "organization"),
                SdkMessage.text(n, "subscriptionType"), SdkMessage.text(n, "apiProvider"), n);
    }

    public String outputStyle() {
        return SdkMessage.text(raw, "output_style");
    }

    public List<String> availableOutputStyles() {
        List<String> out = new ArrayList<>();
        JsonNode arr = raw.get("available_output_styles");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(n.asText());
            }
        }
        return out;
    }

    public Integer pid() {
        JsonNode n = raw.get("pid");
        return (n == null || !n.isNumber()) ? null : n.intValue();
    }

    public String fastModeState() {
        return SdkMessage.text(raw, "fast_mode_state");
    }

    public record ModelInfo(String value, String displayName, String description, JsonNode raw) {
    }

    public record SlashCommand(String name, String description, String argumentHint, JsonNode raw) {
    }

    public record AgentInfo(String name, String description, String model, JsonNode raw) {
    }

    public record AccountInfo(String email, String organization, String subscriptionType,
                              String apiProvider, JsonNode raw) {
    }
}
