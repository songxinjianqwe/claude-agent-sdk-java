package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.permission.PermissionResult;
import com.anthropic.claude.agent.testkit.MockClaude;
import com.anthropic.claude.agent.testkit.MockClaudeLauncher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Phase 4: full can_use_tool round trip over a real subprocess. The mock issues a can_use_tool
 * control request; the SDK invokes the host callback and writes the allow/deny control response back
 * over the still-open stdin (one-shot keeps stdin open until the result).
 */
public class CanUseToolE2ETest {

    @Test
    public void canUseToolAllowRoundTripOverRealProcess() throws Exception {
        Path dir = Files.createTempDirectory("canusetool-e2e");
        Path wrapper = MockClaudeLauncher.install(dir);
        Path record = dir.resolve("record.txt");
        Path scenario = MockClaude.writeScenario(dir,
                "expect \"type\":\"user\"",
                "emit {\"type\":\"control_request\",\"request_id\":\"perm-1\",\"request\":"
                        + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\","
                        + "\"input\":{\"command\":\"ls\"},\"tool_use_id\":\"toolu_9\"}}",
                "expect \"behavior\":\"allow\"",
                "emit {\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\",\"session_id\":\"s\"}",
                "exit 0");

        AtomicReference<String> seenTool = new AtomicReference<>();
        Options options = Options.builder()
                .pathToClaudeCodeExecutable(wrapper.toString())
                .env("MOCK_CLAUDE_SCENARIO", scenario.toString())
                .env("MOCK_CLAUDE_RECORD", record.toString())
                .canUseTool((toolName, input, ctx) -> {
                    seenTool.set(toolName);
                    return PermissionResult.allow();
                })
                .build();

        Query q = ClaudeAgent.query("please run ls", options);
        List<SdkMessage> msgs = q.collect();

        // The can_use_tool control line is NOT surfaced as a message; only the result is.
        assertEquals(1, msgs.size());
        assertEquals("ok", ((SdkResultMessage) msgs.get(0)).result());
        assertEquals("Bash", seenTool.get());

        List<String> recorded = Files.readAllLines(record, StandardCharsets.UTF_8);
        // The mandatory permission flags were spawned.
        assertTrue(recorded.contains("argv\t--permission-prompt-tool"));
        assertTrue(recorded.contains("argv\tstdio"));
        assertTrue(recorded.contains("argv\t--permission-mode"));
        assertTrue(recorded.contains("argv\tdefault"));
        // The allow control response was actually written back over stdin.
        assertTrue("allow response sent", recorded.stream().anyMatch(l ->
                l.startsWith("stdin\t") && l.contains("\"behavior\":\"allow\"")
                        && l.contains("\"toolUseID\":\"toolu_9\"")
                        && l.contains("\"request_id\":\"perm-1\"")));
    }
}
