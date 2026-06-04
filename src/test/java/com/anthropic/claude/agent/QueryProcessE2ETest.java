package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.message.SdkAssistantMessage;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.message.SdkSystemMessage;
import com.anthropic.claude.agent.testkit.MockClaude;
import com.anthropic.claude.agent.testkit.MockClaudeLauncher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/**
 * Phase 2: end-to-end over a real spawned subprocess (mock-claude). Verifies the full pipeline —
 * spawn, write the input user message, read NDJSON, parse, complete on clean exit — and that the
 * mandatory CLI flags were actually passed (via the mock's argv recording).
 */
public class QueryProcessE2ETest {

    @Test
    public void oneShotQueryOverRealProcess() throws Exception {
        Path dir = Files.createTempDirectory("query-e2e");
        Path wrapper = MockClaudeLauncher.install(dir);
        Path record = dir.resolve("record.txt");
        Path scenario = MockClaude.writeScenario(dir,
                "expect \"type\":\"user\"",
                "emit {\"type\":\"system\",\"subtype\":\"init\",\"model\":\"mock\",\"session_id\":\"s\"}",
                "emit {\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"pong\"}]},\"session_id\":\"s\"}",
                "emit {\"type\":\"result\",\"subtype\":\"success\",\"result\":\"pong\",\"session_id\":\"s\"}",
                "exit 0");

        Options options = Options.builder()
                .pathToClaudeCodeExecutable(wrapper.toString())
                .env("MOCK_CLAUDE_SCENARIO", scenario.toString())
                .env("MOCK_CLAUDE_RECORD", record.toString())
                .model("mock")
                .build();

        Query q = ClaudeAgent.query("ping", options);
        List<SdkMessage> msgs = q.collect();

        assertEquals(3, msgs.size());
        assertTrue(msgs.get(0) instanceof SdkSystemMessage);
        assertTrue(msgs.get(1) instanceof SdkAssistantMessage);
        assertEquals("pong", ((SdkAssistantMessage) msgs.get(1)).message().text());
        assertEquals("pong", ((SdkResultMessage) msgs.get(2)).result());

        // The mandatory transport flags were actually spawned.
        List<String> recorded = Files.readAllLines(record, StandardCharsets.UTF_8);
        assertTrue(recorded.contains("argv\t--print"));
        assertTrue(recorded.contains("argv\t--input-format"));
        assertTrue(recorded.contains("argv\t--output-format"));
        assertTrue(recorded.contains("argv\tstream-json"));
        assertTrue(recorded.contains("argv\t--verbose"));
        assertTrue(recorded.contains("argv\t--model"));
        assertTrue(recorded.contains("argv\tmock"));
        // The input user message was actually sent over stdin.
        assertTrue("user message sent to stdin",
                recorded.stream().anyMatch(l -> l.startsWith("stdin\t") && l.contains("\"type\":\"user\"")
                        && l.contains("ping")));
    }
}
