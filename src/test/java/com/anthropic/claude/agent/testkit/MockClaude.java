package com.anthropic.claude.agent.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A scriptable stand-in for the real {@code claude} CLI, used as a real spawned
 * subprocess in transport-level tests (via {@code Options.pathToClaudeCodeExecutable}).
 *
 * <p>It is deliberately dependency-free (no Jackson) so it can run as a plain
 * {@code java -cp ... MockClaude} subprocess. Behaviour is driven by a line-based
 * scenario file whose path is supplied via the {@code MOCK_CLAUDE_SCENARIO} env var
 * (or argv[0] as a fallback).
 *
 * <h3>Scenario directives (one per line)</h3>
 * <ul>
 *   <li>{@code emit <json>} — print the rest of the line verbatim to stdout as one NDJSON line.</li>
 *   <li>{@code emit-raw <text>} — print raw text (for testing non-JSON / malformed lines).</li>
 *   <li>{@code sleep <ms>} — sleep for the given milliseconds.</li>
 *   <li>{@code read} — block-read one line from stdin and discard (sync point).</li>
 *   <li>{@code expect <substr>} — block-read one line from stdin; if it does not contain
 *       {@code <substr>}, write a diagnostic to stderr and exit(3).</li>
 *   <li>{@code exit <code>} — flush and exit with the given code.</li>
 *   <li>{@code close-stdout} — close stdout (to exercise EOF handling on the reader side).</li>
 *   <li>blank line or line starting with {@code #} — ignored.</li>
 * </ul>
 *
 * <p>If {@code MOCK_CLAUDE_RECORD} points to a file, the received argv and every consumed
 * stdin line are appended there so tests can assert on what the SDK actually sent.
 */
public final class MockClaude {

    private MockClaude() {
    }

    public static void main(String[] args) throws Exception {
        // stdout must be UTF-8 and line-flushed so the parent reader sees lines promptly.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        String recordPath = System.getenv("MOCK_CLAUDE_RECORD");
        PrintWriter record = null;
        if (recordPath != null && !recordPath.isEmpty()) {
            record = new PrintWriter(Files.newBufferedWriter(Path.of(recordPath)), true);
            for (String a : args) {
                record.println("argv\t" + a);
            }
            record.flush();
        }

        String scenarioPath = System.getenv("MOCK_CLAUDE_SCENARIO");
        if ((scenarioPath == null || scenarioPath.isEmpty()) && args.length > 0) {
            scenarioPath = args[args.length - 1];
        }
        if (scenarioPath == null || scenarioPath.isEmpty()) {
            System.err.println("MockClaude: no scenario provided (set MOCK_CLAUDE_SCENARIO)");
            System.exit(2);
            return;
        }

        List<String> lines = Files.readAllLines(Path.of(scenarioPath), StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        try {
            int exitCode = run(lines, out, stdin, record);
            out.flush();
            System.exit(exitCode);
        } finally {
            if (record != null) {
                record.close();
            }
        }
    }

    private static int run(List<String> lines, PrintStream out, BufferedReader stdin, PrintWriter record)
            throws IOException, InterruptedException {
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = splitFirst(line);
            String cmd = parts[0];
            String arg = parts[1];

            switch (cmd) {
                case "emit", "emit-raw" -> {
                    out.println(arg);
                    out.flush();
                }
                case "sleep" -> Thread.sleep(Long.parseLong(arg.strip()));
                case "read" -> {
                    String got = stdin.readLine();
                    recordStdin(record, got);
                }
                case "expect" -> {
                    String got = stdin.readLine();
                    recordStdin(record, got);
                    if (got == null || !got.contains(arg)) {
                        System.err.println("MockClaude expect failed: wanted substring [" + arg
                                + "] but got [" + got + "]");
                        return 3;
                    }
                }
                case "close-stdout" -> out.close();
                case "exit" -> {
                    out.flush();
                    return Integer.parseInt(arg.strip());
                }
                default -> {
                    System.err.println("MockClaude: unknown directive: " + cmd);
                    return 4;
                }
            }
        }
        return 0;
    }

    private static void recordStdin(PrintWriter record, String line) {
        if (record != null) {
            record.println("stdin\t" + (line == null ? "<EOF>" : line));
            record.flush();
        }
    }

    /** Split a directive line into [command, rest] on the first run of whitespace. */
    private static String[] splitFirst(String line) {
        int i = 0;
        while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        String cmd = line.substring(0, i);
        String rest = i < line.length() ? line.substring(i + 1) : "";
        // Note: do not strip `rest` — emitted JSON may legitimately matter byte-for-byte.
        return new String[]{cmd, rest};
    }

    /** Build a scenario file in {@code dir} from the given directive lines; returns its path. */
    public static Path writeScenario(Path dir, String... directives) throws IOException {
        Path f = Files.createTempFile(dir, "scenario-", ".txt");
        List<String> all = new ArrayList<>(List.of(directives));
        Files.write(f, all, StandardCharsets.UTF_8);
        return f;
    }
}
