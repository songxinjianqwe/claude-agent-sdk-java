package com.anthropic.claude.agent.testkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Phase 0 scaffolding test: proves the toolchain (JDK17 + Maven + JUnit4) and the
 * real-subprocess mock-claude mechanism both work end to end.
 */
public class MockClaudeSmokeTest {

    @Test
    public void mockClaudeEmitsScriptedNdjsonOverRealProcess() throws Exception {
        Path dir = Files.createTempDirectory("mock-claude-smoke");
        Path wrapper = MockClaudeLauncher.install(dir);
        Path scenario = MockClaude.writeScenario(dir,
                "emit {\"type\":\"system\",\"subtype\":\"init\"}",
                "emit {\"type\":\"result\",\"subtype\":\"success\"}",
                "exit 0");

        ProcessBuilder pb = new ProcessBuilder(wrapper.toString());
        pb.environment().put("MOCK_CLAUDE_SCENARIO", scenario.toString());
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        List<String> stdout = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stdout.add(line);
            }
        }

        boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
        assertTrue("mock-claude did not exit within 30s", exited);
        assertEquals("mock-claude exit code", 0, proc.exitValue());

        assertEquals("expected exactly two NDJSON lines", 2, stdout.size());
        assertEquals("{\"type\":\"system\",\"subtype\":\"init\"}", stdout.get(0));
        assertEquals("{\"type\":\"result\",\"subtype\":\"success\"}", stdout.get(1));
    }

    @Test
    public void mockClaudeRecordsArgvAndConsumedStdin() throws Exception {
        Path dir = Files.createTempDirectory("mock-claude-record");
        Path wrapper = MockClaudeLauncher.install(dir);
        Path record = dir.resolve("record.txt");
        Path scenario = MockClaude.writeScenario(dir,
                "expect hello",
                "emit {\"type\":\"result\",\"subtype\":\"success\"}",
                "exit 0");

        ProcessBuilder pb = new ProcessBuilder(wrapper.toString(), "--print", "--model", "opus");
        pb.environment().put("MOCK_CLAUDE_SCENARIO", scenario.toString());
        pb.environment().put("MOCK_CLAUDE_RECORD", record.toString());
        Process proc = pb.start();

        // Feed a line the scenario's `expect hello` will consume.
        proc.getOutputStream().write("{\"msg\":\"hello world\"}\n".getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().flush();

        List<String> stdout = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stdout.add(line);
            }
        }
        assertTrue("mock-claude did not exit", proc.waitFor(30, TimeUnit.SECONDS));
        assertEquals("clean exit (expect matched)", 0, proc.exitValue());

        List<String> recorded = Files.readAllLines(record, StandardCharsets.UTF_8);
        assertTrue("argv recorded", recorded.contains("argv\t--print"));
        assertTrue("argv recorded", recorded.contains("argv\t--model"));
        assertTrue("argv recorded", recorded.contains("argv\topus"));
        assertTrue("consumed stdin recorded",
                recorded.stream().anyMatch(l -> l.equals("stdin\t{\"msg\":\"hello world\"}")));
        assertEquals(List.of("{\"type\":\"result\",\"subtype\":\"success\"}"), stdout);
    }

    @Test
    public void mockClaudeExpectFailureExitsNonZero() throws Exception {
        Path dir = Files.createTempDirectory("mock-claude-expectfail");
        Path wrapper = MockClaudeLauncher.install(dir);
        Path scenario = MockClaude.writeScenario(dir, "expect NEVER_PRESENT");

        ProcessBuilder pb = new ProcessBuilder(wrapper.toString()).redirectErrorStream(true);
        pb.environment().put("MOCK_CLAUDE_SCENARIO", scenario.toString());
        Process proc = pb.start();
        // Close stdin so `expect` reads EOF (null) and fails.
        proc.getOutputStream().close();

        String all;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            all = sb.toString();
        }
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS));
        assertEquals("expect mismatch should exit 3", 3, proc.exitValue());
        assertTrue("diagnostic on stderr", all.contains("expect failed"));
    }
}
