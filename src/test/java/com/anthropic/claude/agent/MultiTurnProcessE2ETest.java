package com.anthropic.claude.agent;

import static org.junit.Assert.assertEquals;

import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.testkit.MockClaude;
import com.anthropic.claude.agent.testkit.MockClaudeLauncher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Phase 3: real-subprocess multi-turn. The mock reads two user turns from stdin and replies to each
 * in order; the SDK must deliver both results in submission order.
 */
public class MultiTurnProcessE2ETest {

    @Test
    public void streamingTwoTurnsOverRealProcessPreservesOrder() throws Exception {
        Path dir = Files.createTempDirectory("multiturn-e2e");
        Path wrapper = MockClaudeLauncher.install(dir);
        Path record = dir.resolve("record.txt");
        Path scenario = MockClaude.writeScenario(dir,
                "expect \"content\":\"first\"",
                "emit {\"type\":\"result\",\"subtype\":\"success\",\"result\":\"r1\",\"session_id\":\"s\"}",
                "expect \"content\":\"second\"",
                "emit {\"type\":\"result\",\"subtype\":\"success\",\"result\":\"r2\",\"session_id\":\"s\"}",
                "exit 0");

        Options options = Options.builder()
                .pathToClaudeCodeExecutable(wrapper.toString())
                .env("MOCK_CLAUDE_SCENARIO", scenario.toString())
                .env("MOCK_CLAUDE_RECORD", record.toString())
                .build();

        Query q = ClaudeAgent.streamingQuery(options);
        q.streamInput("first");
        q.streamInput("second");
        q.endInput();

        List<String> results = new ArrayList<>();
        for (SdkMessage m : q.messages()) {
            if (m instanceof SdkResultMessage r) {
                results.add(r.result());
            }
        }

        assertEquals(List.of("r1", "r2"), results);

        List<String> recorded = Files.readAllLines(record, StandardCharsets.UTF_8);
        long userTurns = recorded.stream()
                .filter(l -> l.startsWith("stdin\t") && l.contains("\"type\":\"user\"")).count();
        assertEquals(2, userTurns);
    }
}
