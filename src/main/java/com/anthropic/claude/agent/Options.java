package com.anthropic.claude.agent;

import com.anthropic.claude.agent.agent.AgentDefinition;
import com.anthropic.claude.agent.elicitation.OnElicitation;
import com.anthropic.claude.agent.hooks.HookCallback;
import com.anthropic.claude.agent.hooks.HookEvent;
import com.anthropic.claude.agent.hooks.HookRegistration;
import com.anthropic.claude.agent.mcp.McpServerConfig;
import com.anthropic.claude.agent.mcp.SdkMcpServer;
import com.anthropic.claude.agent.message.SdkMessages;
import com.anthropic.claude.agent.permission.CanUseTool;
import com.anthropic.claude.agent.permission.PermissionMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Immutable configuration for a {@link ClaudeAgent#query} call — the Java analogue of the Node SDK
 * {@code Options}. Built via {@link #builder()}.
 *
 * <p>{@link #toCliArgs()} is the single place mapping fields to {@code claude} CLI flags; each entry
 * is annotated with the flag (names verified against {@code claude --help}, v2.1.159). Control-channel
 * options (canUseTool, hooks, in-process mcpServers, agents) are added in later phases and travel via
 * the {@code initialize} control request rather than flags. Anything not modeled can be passed via
 * {@link Builder#extraArg}.
 */
public final class Options {

    private final String pathToClaudeCodeExecutable;
    private final String cwd;
    private final String model;
    private final String fallbackModel;
    private final String systemPrompt;
    private final String appendSystemPrompt;
    private final SystemPrompt systemPromptConfig;
    private final List<String> allowedTools;
    private final List<String> disallowedTools;
    private final List<String> tools;
    private final String jsonSchema;
    private final ThinkingConfig thinking;
    private final PermissionMode permissionMode;
    private final List<String> settingSources;
    private final String settings;
    private final List<String> additionalDirectories;
    private final List<String> mcpConfig;
    private final Map<String, McpServerConfig> externalMcpServers;
    private final List<PluginConfig> plugins;
    private final String resume;
    private final boolean continueConversation;
    private final boolean forkSession;
    private final String sessionId;
    private final Double maxBudgetUsd;
    private final Integer maxTurns;
    private final Integer maxThinkingTokens;
    private final boolean includePartialMessages;
    private final boolean includeHookEvents;
    private final boolean strictMcpConfig;
    private final boolean allowDangerouslySkipPermissions;
    private final List<String> betas;
    private final String effort;
    private final String title;
    private final boolean persistSession;
    private final boolean debug;
    private final String debugFile;
    private final Map<String, String> env;
    private final Consumer<String> stderr;
    private final int bufferCapacity;
    private final Map<String, String> extraArgs;
    private final CanUseTool canUseTool;
    private final OnElicitation onElicitation;
    private final List<HookRegistration> hooks;
    private final List<SdkMcpServer> sdkMcpServers;
    private final String agent;
    private final Map<String, AgentDefinition> agents;

    private Options(Builder b) {
        this.pathToClaudeCodeExecutable = b.pathToClaudeCodeExecutable;
        this.cwd = b.cwd;
        this.model = b.model;
        this.fallbackModel = b.fallbackModel;
        this.systemPrompt = b.systemPrompt;
        this.appendSystemPrompt = b.appendSystemPrompt;
        this.systemPromptConfig = b.systemPromptConfig;
        this.allowedTools = List.copyOf(b.allowedTools);
        this.disallowedTools = List.copyOf(b.disallowedTools);
        this.tools = List.copyOf(b.tools);
        this.jsonSchema = b.jsonSchema;
        this.thinking = b.thinking;
        this.permissionMode = b.permissionMode;
        this.settingSources = List.copyOf(b.settingSources);
        this.settings = b.settings;
        this.additionalDirectories = List.copyOf(b.additionalDirectories);
        this.mcpConfig = List.copyOf(b.mcpConfig);
        this.externalMcpServers = Map.copyOf(b.externalMcpServers);
        this.plugins = List.copyOf(b.plugins);
        this.resume = b.resume;
        this.continueConversation = b.continueConversation;
        this.forkSession = b.forkSession;
        this.sessionId = b.sessionId;
        this.maxBudgetUsd = b.maxBudgetUsd;
        this.maxTurns = b.maxTurns;
        this.maxThinkingTokens = b.maxThinkingTokens;
        this.includePartialMessages = b.includePartialMessages;
        this.includeHookEvents = b.includeHookEvents;
        this.strictMcpConfig = b.strictMcpConfig;
        this.allowDangerouslySkipPermissions = b.allowDangerouslySkipPermissions;
        this.betas = List.copyOf(b.betas);
        this.effort = b.effort;
        this.title = b.title;
        this.persistSession = b.persistSession;
        this.debug = b.debug;
        this.debugFile = b.debugFile;
        this.env = Map.copyOf(b.env);
        this.stderr = b.stderr;
        this.bufferCapacity = b.bufferCapacity;
        this.extraArgs = new LinkedHashMap<>(b.extraArgs);
        this.canUseTool = b.canUseTool;
        this.onElicitation = b.onElicitation;
        this.hooks = List.copyOf(b.hooks);
        this.sdkMcpServers = List.copyOf(b.sdkMcpServers);
        this.agent = b.agent;
        this.agents = Map.copyOf(b.agents);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Options defaults() {
        return builder().build();
    }

    // --- accessors -------------------------------------------------------------------------------

    public String pathToClaudeCodeExecutable() {
        return pathToClaudeCodeExecutable;
    }

    public String cwd() {
        return cwd;
    }

    public String model() {
        return model;
    }

    public PermissionMode permissionMode() {
        return permissionMode;
    }

    public boolean includePartialMessages() {
        return includePartialMessages;
    }

    public Map<String, String> env() {
        return env;
    }

    public Consumer<String> stderr() {
        return stderr;
    }

    public int bufferCapacity() {
        return bufferCapacity;
    }

    public CanUseTool canUseTool() {
        return canUseTool;
    }

    public OnElicitation onElicitation() {
        return onElicitation;
    }

    public List<HookRegistration> hooks() {
        return hooks;
    }

    public List<SdkMcpServer> sdkMcpServers() {
        return sdkMcpServers;
    }

    /**
     * The CLI flags this run requires. The {@code --print --input-format/--output-format stream-json
     * --verbose} quartet is mandatory (stream-json output requires {@code --verbose}; stream-json
     * input forces stream-json output).
     */
    public List<String> toCliArgs() {
        List<String> a = new ArrayList<>();
        // Mandatory transport flags:
        a.add("--print");
        a.add("--input-format");
        a.add("stream-json");
        a.add("--output-format");
        a.add("stream-json");
        a.add("--verbose");

        addValue(a, "--model", model);                                  // model
        addValue(a, "--fallback-model", fallbackModel);                 // fallbackModel
        addValue(a, "--agent", agent);                                  // agent (select)
        if (!agents.isEmpty()) {                                        // agents (define) -> JSON
            addValue(a, "--agents", agentsJson());
        }
        if (systemPromptConfig instanceof SystemPrompt.Text t) {        // systemPrompt (typed: raw)
            addValue(a, "--system-prompt", t.text());
        } else if (systemPromptConfig instanceof SystemPrompt.Preset p) { // systemPrompt (typed: preset)
            addValue(a, "--append-system-prompt", p.append());
            addFlag(a, "--exclude-dynamic-system-prompt-sections", p.excludeDynamicSections());
        } else {                                                        // systemPrompt (string convenience)
            addValue(a, "--system-prompt", systemPrompt);
            addValue(a, "--append-system-prompt", appendSystemPrompt);
        }
        // Permission mode: an explicit mode wins; otherwise, when a canUseTool callback is present
        // we default to "default" so "ask" decisions are actually routed to the host (wire §E.5).
        PermissionMode effectiveMode = permissionMode != null
                ? permissionMode
                : (canUseTool != null ? PermissionMode.DEFAULT : null);
        if (effectiveMode != null) {
            addValue(a, "--permission-mode", effectiveMode.wire());
        }
        // canUseTool routes "ask" prompts over stdio via the magic --permission-prompt-tool value.
        if (canUseTool != null) {
            addValue(a, "--permission-prompt-tool", "stdio");
        }
        addVariadic(a, "--allowed-tools", allowedTools);                // allowedTools (no-prompt)
        addVariadic(a, "--disallowed-tools", disallowedTools);          // disallowedTools
        addVariadic(a, "--tools", tools);                               // tools (limit the tool set)
        addValue(a, "--json-schema", jsonSchema);                       // jsonSchema (structured output)
        addVariadic(a, "--add-dir", additionalDirectories);             // additionalDirectories
        addVariadic(a, "--mcp-config", mcpConfig);                      // external MCP configs (raw)
        if (!externalMcpServers.isEmpty()) {                           // typed external MCP -> --mcp-config JSON
            addValue(a, "--mcp-config", mcpServersJson());
        }
        for (PluginConfig p : plugins) {                               // plugins -> --plugin-dir per path
            if ("local".equals(p.type()) && p.path() != null) {
                addValue(a, "--plugin-dir", p.path());
            }
        }
        if (!settingSources.isEmpty()) {                                // settingSources (comma-joined)
            addValue(a, "--setting-sources", String.join(",", settingSources));
        }
        addValue(a, "--settings", settings);                            // settings
        addValue(a, "--resume", resume);                                // resume
        addFlag(a, "--continue", continueConversation);                 // continue
        addFlag(a, "--fork-session", forkSession);                      // forkSession
        addValue(a, "--session-id", sessionId);                         // sessionId
        if (maxBudgetUsd != null) {                                     // maxBudgetUsd
            addValue(a, "--max-budget-usd", String.valueOf(maxBudgetUsd));
        }
        if (maxTurns != null) {                                         // maxTurns (real hidden flag)
            addValue(a, "--max-turns", String.valueOf(maxTurns));
        }
        // thinking (preferred) takes precedence over the deprecated maxThinkingTokens.
        if (thinking != null) {
            switch (thinking.type()) {
                case DISABLED -> addValue(a, "--thinking", "disabled");
                case ADAPTIVE -> addValue(a, "--thinking", "adaptive");
                case ENABLED -> {
                    if (thinking.budgetTokens() != null) {
                        addValue(a, "--max-thinking-tokens", String.valueOf(thinking.budgetTokens()));
                    } else {
                        addValue(a, "--thinking", "adaptive");
                    }
                }
            }
            // display only applies to non-disabled thinking (matches SDK's --thinking-display)
            if (thinking.type() != ThinkingConfig.Type.DISABLED && thinking.display() != null) {
                addValue(a, "--thinking-display", thinking.display());
            }
        } else if (maxThinkingTokens != null) {                         // deprecated, start-time budget
            addValue(a, "--max-thinking-tokens", String.valueOf(maxThinkingTokens));
        }
        addFlag(a, "--include-partial-messages", includePartialMessages);
        addFlag(a, "--include-hook-events", includeHookEvents);
        addFlag(a, "--strict-mcp-config", strictMcpConfig);
        addFlag(a, "--allow-dangerously-skip-permissions", allowDangerouslySkipPermissions);
        addVariadic(a, "--betas", betas);                               // betas
        addValue(a, "--effort", effort);                                // effort
        addValue(a, "--name", title);                                   // title -> --name
        addFlag(a, "--no-session-persistence", !persistSession);        // persistSession=false
        addFlag(a, "--debug", debug);                                   // debug
        addValue(a, "--debug-file", debugFile);                         // debugFile

        // Escape hatch for any flag not modeled (Node: extraArgs). value==null → boolean flag.
        for (Map.Entry<String, String> e : extraArgs.entrySet()) {
            String key = e.getKey().startsWith("--") ? e.getKey() : "--" + e.getKey();
            a.add(key);
            if (e.getValue() != null) {
                a.add(e.getValue());
            }
        }
        return a;
    }

    /** The full argv: executable + {@link #toCliArgs()}. */
    public List<String> command() {
        List<String> cmd = new ArrayList<>();
        cmd.add(pathToClaudeCodeExecutable);
        cmd.addAll(toCliArgs());
        return cmd;
    }

    /** Serialize the custom agents map to the JSON string the {@code --agents} flag expects. */
    private String agentsJson() {
        ObjectNode root = SdkMessages.mapper().createObjectNode();
        agents.forEach((name, def) -> {
            ObjectNode n = root.putObject(name);
            n.put("description", def.description());
            n.put("prompt", def.prompt());
            if (def.model() != null) {
                n.put("model", def.model());
            }
            if (def.tools() != null) {
                ArrayNode arr = n.putArray("tools");
                def.tools().forEach(arr::add);
            }
            if (def.disallowedTools() != null) {
                ArrayNode arr = n.putArray("disallowedTools");
                def.disallowedTools().forEach(arr::add);
            }
            if (def.maxTurns() != null) {
                n.put("maxTurns", def.maxTurns().intValue());
            }
            if (def.permissionMode() != null) {
                n.put("permissionMode", def.permissionMode().wire());
            }
            if (def.skills() != null) {
                ArrayNode arr = n.putArray("skills");
                def.skills().forEach(arr::add);
            }
            if (def.initialPrompt() != null) {
                n.put("initialPrompt", def.initialPrompt());
            }
            if (def.effort() != null) {
                n.put("effort", def.effort());
            }
            if (def.background() != null) {
                n.put("background", def.background());
            }
            if (def.memory() != null) {
                n.put("memory", def.memory());
            }
        });
        try {
            return SdkMessages.mapper().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize agents", e);
        }
    }

    /** Serialize typed external MCP servers to the {@code --mcp-config} JSON ({@code {mcpServers:{...}}}). */
    private String mcpServersJson() {
        ObjectNode root = SdkMessages.mapper().createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        externalMcpServers.forEach((name, cfg) -> {
            ObjectNode n = servers.putObject(name);
            if (cfg instanceof McpServerConfig.Stdio s) {
                n.put("type", "stdio");
                n.put("command", s.command());
                if (s.args() != null && !s.args().isEmpty()) {
                    ArrayNode arr = n.putArray("args");
                    s.args().forEach(arr::add);
                }
                if (s.env() != null && !s.env().isEmpty()) {
                    ObjectNode e = n.putObject("env");
                    s.env().forEach(e::put);
                }
                if (s.timeout() != null) {
                    n.put("timeout", s.timeout().intValue());
                }
            } else if (cfg instanceof McpServerConfig.Sse s) {
                n.put("type", "sse");
                n.put("url", s.url());
                putHeaders(n, s.headers());
                if (s.timeout() != null) {
                    n.put("timeout", s.timeout().intValue());
                }
                if (s.alwaysLoad() != null) {
                    n.put("alwaysLoad", s.alwaysLoad());
                }
            } else if (cfg instanceof McpServerConfig.Http s) {
                n.put("type", "http");
                n.put("url", s.url());
                putHeaders(n, s.headers());
                if (s.timeout() != null) {
                    n.put("timeout", s.timeout().intValue());
                }
                if (s.alwaysLoad() != null) {
                    n.put("alwaysLoad", s.alwaysLoad());
                }
            }
        });
        try {
            return SdkMessages.mapper().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize mcpServers", e);
        }
    }

    private static void putHeaders(ObjectNode n, Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            ObjectNode h = n.putObject("headers");
            headers.forEach(h::put);
        }
    }

    private static void addValue(List<String> args, String flag, String value) {
        if (value != null && !value.isBlank()) {
            args.add(flag);
            args.add(value);
        }
    }

    private static void addFlag(List<String> args, String flag, boolean on) {
        if (on) {
            args.add(flag);
        }
    }

    private static void addVariadic(List<String> args, String flag, List<String> values) {
        if (!values.isEmpty()) {
            args.add(flag);
            args.addAll(values);
        }
    }

    // --- builder ---------------------------------------------------------------------------------

    /** Mutable builder for {@link Options}. */
    public static final class Builder {
        private String pathToClaudeCodeExecutable = "claude";
        private String cwd;
        private String model;
        private String fallbackModel;
        private String systemPrompt;
        private String appendSystemPrompt;
        private SystemPrompt systemPromptConfig;
        private final List<String> allowedTools = new ArrayList<>();
        private final List<String> disallowedTools = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();
        private String jsonSchema;
        private ThinkingConfig thinking;
        private PermissionMode permissionMode;
        private final List<String> settingSources = new ArrayList<>();
        private String settings;
        private final List<String> additionalDirectories = new ArrayList<>();
        private final List<String> mcpConfig = new ArrayList<>();
        private final Map<String, McpServerConfig> externalMcpServers = new LinkedHashMap<>();
        private final List<PluginConfig> plugins = new ArrayList<>();
        private String resume;
        private boolean continueConversation = false;
        private boolean forkSession = false;
        private String sessionId;
        private Double maxBudgetUsd;
        private Integer maxTurns;
        private Integer maxThinkingTokens;
        private boolean includePartialMessages = false;
        private boolean includeHookEvents = false;
        private boolean strictMcpConfig = false;
        private boolean allowDangerouslySkipPermissions = false;
        private final List<String> betas = new ArrayList<>();
        private String effort;
        private String title;
        private boolean persistSession = true;
        private boolean debug = false;
        private String debugFile;
        private final Map<String, String> env = new LinkedHashMap<>();
        private Consumer<String> stderr;
        private int bufferCapacity = 1024;
        private final Map<String, String> extraArgs = new LinkedHashMap<>();
        private CanUseTool canUseTool;
        private OnElicitation onElicitation;
        private final List<HookRegistration> hooks = new ArrayList<>();
        private final List<SdkMcpServer> sdkMcpServers = new ArrayList<>();
        private String agent;
        private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();

        public Builder pathToClaudeCodeExecutable(String path) {
            if (path != null && !path.isBlank()) {
                this.pathToClaudeCodeExecutable = path;
            }
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder fallbackModel(String model) {
            this.fallbackModel = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder appendSystemPrompt(String append) {
            this.appendSystemPrompt = append;
            return this;
        }

        /** Typed system prompt (raw text or the claude_code preset); takes precedence over the string setters. */
        public Builder systemPrompt(SystemPrompt systemPrompt) {
            this.systemPromptConfig = systemPrompt;
            return this;
        }

        public Builder allowedTools(String... tools) {
            this.allowedTools.addAll(List.of(tools));
            return this;
        }

        public Builder disallowedTools(String... tools) {
            this.disallowedTools.addAll(List.of(tools));
            return this;
        }

        /** Limit which built-in tools are available ({@code --tools}); distinct from allowedTools. */
        public Builder tools(String... tools) {
            this.tools.addAll(List.of(tools));
            return this;
        }

        /** JSON Schema for structured output ({@code --json-schema}). */
        public Builder jsonSchema(String jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        /** Extended-thinking config (preferred over {@link #maxThinkingTokens}). */
        public Builder thinking(ThinkingConfig thinking) {
            this.thinking = thinking;
            return this;
        }

        public Builder permissionMode(PermissionMode mode) {
            this.permissionMode = mode;
            return this;
        }

        public Builder settingSources(String... sources) {
            this.settingSources.addAll(List.of(sources));
            return this;
        }

        public Builder settings(String settings) {
            this.settings = settings;
            return this;
        }

        public Builder additionalDirectories(String... dirs) {
            this.additionalDirectories.addAll(List.of(dirs));
            return this;
        }

        /** External MCP server config files or JSON strings ({@code --mcp-config}). */
        public Builder mcpConfig(String... configs) {
            this.mcpConfig.addAll(List.of(configs));
            return this;
        }

        /** Load a session plugin ({@code --plugin-dir}). */
        public Builder plugin(PluginConfig plugin) {
            this.plugins.add(plugin);
            return this;
        }

        /** Resume a conversation by session id. */
        public Builder resume(String sessionId) {
            this.resume = sessionId;
            return this;
        }

        /** Continue the most recent conversation in {@code cwd}. */
        public Builder continueConversation(boolean v) {
            this.continueConversation = v;
            return this;
        }

        public Builder forkSession(boolean v) {
            this.forkSession = v;
            return this;
        }

        public Builder sessionId(String uuid) {
            this.sessionId = uuid;
            return this;
        }

        public Builder maxBudgetUsd(double amount) {
            this.maxBudgetUsd = amount;
            return this;
        }

        /** Maximum number of turns ({@code --max-turns}). */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /** Start-time thinking-token budget ({@code --max-thinking-tokens}). */
        public Builder maxThinkingTokens(int maxThinkingTokens) {
            this.maxThinkingTokens = maxThinkingTokens;
            return this;
        }

        public Builder includePartialMessages(boolean v) {
            this.includePartialMessages = v;
            return this;
        }

        public Builder includeHookEvents(boolean v) {
            this.includeHookEvents = v;
            return this;
        }

        public Builder strictMcpConfig(boolean v) {
            this.strictMcpConfig = v;
            return this;
        }

        public Builder allowDangerouslySkipPermissions(boolean v) {
            this.allowDangerouslySkipPermissions = v;
            return this;
        }

        public Builder betas(String... betas) {
            this.betas.addAll(List.of(betas));
            return this;
        }

        public Builder effort(String level) {
            this.effort = level;
            return this;
        }

        /** Session display name ({@code --name}). */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** Whether the session is persisted to disk (default true). False adds --no-session-persistence. */
        public Builder persistSession(boolean v) {
            this.persistSession = v;
            return this;
        }

        public Builder debug(boolean v) {
            this.debug = v;
            return this;
        }

        public Builder debugFile(String path) {
            this.debugFile = path;
            return this;
        }

        public Builder env(String key, String value) {
            this.env.put(key, value);
            return this;
        }

        public Builder env(Map<String, String> values) {
            this.env.putAll(values);
            return this;
        }

        public Builder stderr(Consumer<String> stderr) {
            this.stderr = stderr;
            return this;
        }

        public Builder bufferCapacity(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException("bufferCapacity must be >= 1");
            }
            this.bufferCapacity = capacity;
            return this;
        }

        /** Pass an arbitrary CLI flag not modeled here. {@code value==null} → boolean flag. */
        public Builder extraArg(String flag, String value) {
            this.extraArgs.put(flag, value);
            return this;
        }

        /**
         * Host tool-permission callback. Setting it makes the SDK launch claude with
         * {@code --permission-prompt-tool stdio} (and a default {@code --permission-mode default}
         * unless a mode is set), so "ask" decisions are routed to this callback.
         */
        public Builder canUseTool(CanUseTool canUseTool) {
            this.canUseTool = canUseTool;
            return this;
        }

        /** Host callback for MCP elicitation requests. */
        public Builder onElicitation(OnElicitation onElicitation) {
            this.onElicitation = onElicitation;
            return this;
        }

        /** Register a hook callback for {@code event} (no matcher). */
        public Builder hook(HookEvent event, HookCallback callback) {
            return hook(event, null, null, callback);
        }

        /** Register a hook callback for {@code event} filtered by {@code matcher}. */
        public Builder hook(HookEvent event, String matcher, HookCallback callback) {
            return hook(event, matcher, null, callback);
        }

        /** Register a hook callback for {@code event} with a matcher and timeout (seconds). */
        public Builder hook(HookEvent event, String matcher, Integer timeout, HookCallback callback) {
            this.hooks.add(new HookRegistration(event, matcher, timeout, callback));
            return this;
        }

        /** Register an in-process MCP server (tools hosted in this JVM). */
        public Builder mcpServer(SdkMcpServer server) {
            this.sdkMcpServers.add(server);
            return this;
        }

        /** Register a typed external MCP server (stdio/sse/http) under {@code name} ({@code --mcp-config}). */
        public Builder mcpServer(String name, McpServerConfig config) {
            this.externalMcpServers.put(name, config);
            return this;
        }

        /** Select the agent for this session ({@code --agent}). */
        public Builder agent(String name) {
            this.agent = name;
            return this;
        }

        /** Define a custom agent ({@code --agents} JSON). */
        public Builder defineAgent(String name, AgentDefinition definition) {
            this.agents.put(name, definition);
            return this;
        }

        public Options build() {
            return new Options(this);
        }
    }
}
