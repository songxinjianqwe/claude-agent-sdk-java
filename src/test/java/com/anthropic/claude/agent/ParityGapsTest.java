package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.mcp.McpServerConfig;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkMessages;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.testkit.FakeTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.Test;

/** Cross-check parity additions: structured_output, keep_alive, systemPrompt preset, plugins, MCP config, structured input. */
public class ParityGapsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertFlagValue(List<String> args, String flag, String expected) {
        int i = args.indexOf(flag);
        assertTrue("missing flag " + flag, i >= 0 && i + 1 < args.size());
        assertEquals(expected, args.get(i + 1));
    }

    @Test
    public void structuredOutputAccessor() {
        SdkResultMessage r = (SdkResultMessage) SdkMessages.parse(
                "{\"type\":\"result\",\"subtype\":\"success\",\"structured_output\":{\"name\":\"x\"}}");
        assertEquals("x", r.structuredOutput().get("name").asText());

        SdkResultMessage none = (SdkResultMessage) SdkMessages.parse(
                "{\"type\":\"result\",\"subtype\":\"success\"}");
        assertEquals(null, none.structuredOutput());
    }

    @Test
    public void keepAliveAndEnvFramesAreNotEmitted() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.query("hi", Options.defaults(), t);
        t.feedLine("{\"type\":\"keep_alive\"}");
        t.feedLine("{\"type\":\"update_environment_variables\",\"variables\":{}}");
        t.feedLine("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\"}");
        t.feedExit(0);

        List<SdkMessage> msgs = q.collect();
        assertEquals(1, msgs.size());
        assertTrue(msgs.get(0) instanceof SdkResultMessage);
    }

    @Test
    public void systemPromptPresetMaps() {
        List<String> text = Options.builder()
                .systemPrompt(SystemPrompt.text("be terse")).build().toCliArgs();
        assertFlagValue(text, "--system-prompt", "be terse");

        List<String> preset = Options.builder()
                .systemPrompt(SystemPrompt.preset("also kind", true)).build().toCliArgs();
        assertFalse("preset keeps the default prompt", preset.contains("--system-prompt"));
        assertFlagValue(preset, "--append-system-prompt", "also kind");
        assertTrue(preset.contains("--exclude-dynamic-system-prompt-sections"));
    }

    @Test
    public void pluginsMapToPluginDir() {
        List<String> a = Options.builder()
                .plugin(PluginConfig.local("/path/to/plugin"))
                .build().toCliArgs();
        assertFlagValue(a, "--plugin-dir", "/path/to/plugin");
    }

    @Test
    public void externalMcpServersSerializeToMcpConfigJson() {
        List<String> a = Options.builder()
                .mcpServer("fs", McpServerConfig.stdio("npx", "-y", "@modelcontextprotocol/server-fs"))
                .mcpServer("api", McpServerConfig.http("https://example.com/mcp"))
                .build().toCliArgs();
        int i = a.indexOf("--mcp-config");
        assertTrue(i >= 0);
        JsonNode cfg = parse(a.get(i + 1)).get("mcpServers");
        assertEquals("stdio", cfg.get("fs").get("type").asText());
        assertEquals("npx", cfg.get("fs").get("command").asText());
        assertEquals("-y", cfg.get("fs").get("args").get(0).asText());
        assertEquals("http", cfg.get("api").get("type").asText());
        assertEquals("https://example.com/mcp", cfg.get("api").get("url").asText());
    }

    @Test
    public void structuredStreamInputWritesContentBlocksAndParentToolUseId() {
        FakeTransport t = new FakeTransport();
        Query q = ClaudeAgent.streamingQuery(Options.defaults(), t);

        JsonNode blocks = parse("[{\"type\":\"text\",\"text\":\"hi\"},"
                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\"}}]");
        q.streamInput(UserMessageInput.blocks(blocks).withParentToolUseId("tu1"));

        assertEquals(1, t.written.size());
        JsonNode sent = parse(t.written.get(0));
        assertEquals("user", sent.get("type").asText());
        assertEquals("hi", sent.get("message").get("content").get(0).get("text").asText());
        assertEquals("image", sent.get("message").get("content").get(1).get("type").asText());
        assertEquals("tu1", sent.get("parent_tool_use_id").asText());
    }
}
