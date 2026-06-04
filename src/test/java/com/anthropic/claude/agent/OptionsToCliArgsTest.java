package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.agent.AgentDefinition;
import com.anthropic.claude.agent.permission.PermissionMode;
import java.util.List;
import org.junit.Test;

/** Phase 3: Options → CLI argv mapping. */
public class OptionsToCliArgsTest {

    /** Index of {@code flag} in args, asserting its immediate value equals {@code expected}. */
    private static void assertFlagValue(List<String> args, String flag, String expected) {
        int i = args.indexOf(flag);
        assertTrue("missing flag " + flag + " in " + args, i >= 0);
        assertTrue("flag " + flag + " has no value", i + 1 < args.size());
        assertEquals(expected, args.get(i + 1));
    }

    @Test
    public void mandatoryFlagsAlwaysPresent() {
        List<String> a = Options.defaults().toCliArgs();
        assertTrue(a.contains("--print"));
        assertTrue(a.contains("--verbose"));
        assertFlagValue(a, "--input-format", "stream-json");
        assertFlagValue(a, "--output-format", "stream-json");
    }

    @Test
    public void defaultsDoNotIncludeOptionalFlags() {
        List<String> a = Options.defaults().toCliArgs();
        assertFalse(a.contains("--model"));
        assertFalse(a.contains("--permission-mode"));
        assertFalse(a.contains("--continue"));
        assertFalse(a.contains("--allow-dangerously-skip-permissions"));
        assertFalse(a.contains("--no-session-persistence"));
    }

    @Test
    public void scalarFlagsMap() {
        List<String> a = Options.builder()
                .model("claude-opus-4-7")
                .fallbackModel("claude-sonnet-4-6")
                .systemPrompt("be terse")
                .appendSystemPrompt("also be kind")
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .resume("sess-123")
                .sessionId("uuid-1")
                .settings("/path/settings.json")
                .maxBudgetUsd(2.5)
                .effort("high")
                .title("my session")
                .debugFile("/tmp/d.log")
                .build()
                .toCliArgs();

        assertFlagValue(a, "--model", "claude-opus-4-7");
        assertFlagValue(a, "--fallback-model", "claude-sonnet-4-6");
        assertFlagValue(a, "--system-prompt", "be terse");
        assertFlagValue(a, "--append-system-prompt", "also be kind");
        assertFlagValue(a, "--permission-mode", "acceptEdits");
        assertFlagValue(a, "--resume", "sess-123");
        assertFlagValue(a, "--session-id", "uuid-1");
        assertFlagValue(a, "--settings", "/path/settings.json");
        assertFlagValue(a, "--max-budget-usd", "2.5");
        assertFlagValue(a, "--effort", "high");
        assertFlagValue(a, "--name", "my session");
        assertFlagValue(a, "--debug-file", "/tmp/d.log");
    }

    @Test
    public void booleanFlagsMap() {
        List<String> a = Options.builder()
                .continueConversation(true)
                .forkSession(true)
                .includePartialMessages(true)
                .includeHookEvents(true)
                .strictMcpConfig(true)
                .allowDangerouslySkipPermissions(true)
                .persistSession(false)
                .debug(true)
                .build()
                .toCliArgs();

        assertTrue(a.contains("--continue"));
        assertTrue(a.contains("--fork-session"));
        assertTrue(a.contains("--include-partial-messages"));
        assertTrue(a.contains("--include-hook-events"));
        assertTrue(a.contains("--strict-mcp-config"));
        assertTrue(a.contains("--allow-dangerously-skip-permissions"));
        assertTrue(a.contains("--no-session-persistence"));
        assertTrue(a.contains("--debug"));
    }

    @Test
    public void variadicAndListFlagsMap() {
        List<String> a = Options.builder()
                .allowedTools("Read", "Grep")
                .disallowedTools("Bash")
                .additionalDirectories("/a", "/b")
                .settingSources("user", "project")
                .betas("beta-1")
                .mcpConfig("/path/mcp.json")
                .build()
                .toCliArgs();

        // variadic: flag followed by each value
        int at = a.indexOf("--allowed-tools");
        assertEquals("Read", a.get(at + 1));
        assertEquals("Grep", a.get(at + 2));
        assertEquals("Bash", a.get(a.indexOf("--disallowed-tools") + 1));
        int ad = a.indexOf("--add-dir");
        assertEquals("/a", a.get(ad + 1));
        assertEquals("/b", a.get(ad + 2));
        // setting-sources joined with comma
        assertFlagValue(a, "--setting-sources", "user,project");
        assertEquals("beta-1", a.get(a.indexOf("--betas") + 1));
        assertEquals("/path/mcp.json", a.get(a.indexOf("--mcp-config") + 1));
    }

    @Test
    public void maxTurnsAndMaxThinkingTokensMap() {
        List<String> a = Options.builder()
                .maxTurns(5)
                .maxThinkingTokens(8000)
                .build()
                .toCliArgs();
        assertFlagValue(a, "--max-turns", "5");
        assertFlagValue(a, "--max-thinking-tokens", "8000");
    }

    @Test
    public void extraArgsEscapeHatch() {
        List<String> a = Options.builder()
                .extraArg("some-future-flag", "v")     // not a typed option → passthrough
                .extraArg("--some-boolean-flag", null) // already-prefixed boolean flag
                .build()
                .toCliArgs();

        assertFlagValue(a, "--some-future-flag", "v");
        assertTrue(a.contains("--some-boolean-flag"));
    }

    @Test
    public void canUseToolAddsPermissionPromptToolAndDefaultMode() {
        java.util.List<String> a = Options.builder()
                .canUseTool((tool, input, ctx) -> com.anthropic.claude.agent.permission.PermissionResult.allow())
                .build()
                .toCliArgs();
        assertFlagValue(a, "--permission-prompt-tool", "stdio");
        // canUseTool with no explicit mode defaults to --permission-mode default (wire §E.5)
        assertFlagValue(a, "--permission-mode", "default");
    }

    @Test
    public void explicitPermissionModeWinsOverCanUseToolDefault() {
        java.util.List<String> a = Options.builder()
                .canUseTool((tool, input, ctx) -> com.anthropic.claude.agent.permission.PermissionResult.allow())
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .build()
                .toCliArgs();
        assertFlagValue(a, "--permission-prompt-tool", "stdio");
        assertFlagValue(a, "--permission-mode", "acceptEdits");
    }

    @Test
    public void thinkingToolsAndJsonSchemaMap() {
        assertFlagValue(Options.builder().thinking(ThinkingConfig.disabled()).build().toCliArgs(),
                "--thinking", "disabled");
        assertFlagValue(Options.builder().thinking(ThinkingConfig.adaptive()).build().toCliArgs(),
                "--thinking", "adaptive");
        // enabled+budget maps to --max-thinking-tokens (matches the published SDK)
        assertFlagValue(Options.builder().thinking(ThinkingConfig.enabled(5000)).build().toCliArgs(),
                "--max-thinking-tokens", "5000");
        // display maps to --thinking-display (hidden flag the SDK uses)
        assertFlagValue(Options.builder().thinking(ThinkingConfig.adaptive().withDisplay("summarized"))
                .build().toCliArgs(), "--thinking-display", "summarized");

        List<String> a = Options.builder()
                .tools("Read", "Bash")
                .jsonSchema("{\"type\":\"object\"}")
                .build()
                .toCliArgs();
        assertEquals("Read", a.get(a.indexOf("--tools") + 1));
        assertFlagValue(a, "--json-schema", "{\"type\":\"object\"}");
    }

    @Test
    public void agentSelectionAndDefinitionsMap() {
        List<String> a = Options.builder()
                .agent("reviewer")
                .defineAgent("reviewer",
                        AgentDefinition.of("Reviews code", "You review code")
                                .withModel("opus")
                                .withMaxTurns(3)
                                .withPermissionMode(PermissionMode.PLAN)
                                .withSkills(List.of("lint"))
                                .withEffort("high")
                                .withBackground(true)
                                .withMemory("project"))
                .build()
                .toCliArgs();
        assertFlagValue(a, "--agent", "reviewer");
        int i = a.indexOf("--agents");
        assertTrue("--agents present", i >= 0);
        String json = a.get(i + 1);
        assertTrue(json.contains("\"reviewer\""));
        assertTrue(json.contains("\"description\":\"Reviews code\""));
        assertTrue(json.contains("\"prompt\":\"You review code\""));
        assertTrue(json.contains("\"model\":\"opus\""));
        assertTrue(json.contains("\"maxTurns\":3"));
        assertTrue(json.contains("\"permissionMode\":\"plan\""));
        assertTrue(json.contains("\"skills\":[\"lint\"]"));
        assertTrue(json.contains("\"effort\":\"high\""));
        assertTrue(json.contains("\"background\":true"));
        assertTrue(json.contains("\"memory\":\"project\""));
    }

    @Test
    public void commandPrependsExecutable() {
        List<String> cmd = Options.builder()
                .pathToClaudeCodeExecutable("/usr/local/bin/claude")
                .build()
                .command();
        assertEquals("/usr/local/bin/claude", cmd.get(0));
        assertEquals("--print", cmd.get(1));
    }

    @Test
    public void envAndUnsetEnvDoNotLeakIntoCliArgs() {
        Options o = Options.builder()
                .env("PATH", "/custom/bin")
                .unsetEnv("CLAUDECODE", "TMUX")
                .build();
        // env / unsetEnv travel via ProcessBuilder.environment(), never as CLI flags.
        List<String> a = o.toCliArgs();
        assertFalse(a.contains("PATH"));
        assertFalse(a.contains("/custom/bin"));
        assertFalse(a.contains("CLAUDECODE"));
        assertFalse(a.contains("TMUX"));
        // builder stores the unset list in order.
        assertEquals(List.of("CLAUDECODE", "TMUX"), o.unsetEnv());
    }

    @Test
    public void envWithNullValueBecomesUnset() {
        Options o = Options.builder()
                .env("FOO", "bar")
                .env("CLAUDECODE", null)   // null value → unset (Node SDK env=undefined)
                .build();
        assertEquals("bar", o.env().get("FOO"));
        assertFalse(o.env().containsKey("CLAUDECODE"));
        assertTrue(o.unsetEnv().contains("CLAUDECODE"));

        // bulk form: a null value in the map also becomes an unset
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("TMUX", null);
        m.put("KEEP", "1");
        Options o2 = Options.builder().env(m).build();
        assertTrue(o2.unsetEnv().contains("TMUX"));
        assertEquals("1", o2.env().get("KEEP"));
        assertFalse(o2.env().containsKey("TMUX"));
    }
}
