import com.anthropic.claude.agent.ClaudeAgent;
import com.anthropic.claude.agent.Options;
import com.anthropic.claude.agent.Query;
import com.anthropic.claude.agent.ThinkingConfig;
import com.anthropic.claude.agent.hooks.HookEvent;
import com.anthropic.claude.agent.hooks.HookOutput;
import com.anthropic.claude.agent.mcp.McpToolResult;
import com.anthropic.claude.agent.mcp.SdkMcpServer;
import com.anthropic.claude.agent.message.SdkAssistantMessage;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkPartialAssistantMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.permission.PermissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-turn (no process reuse) — the simplest production-shaped pattern.
 *
 * <p>Every call to {@link #ask} spawns a fresh {@code claude} CLI subprocess via
 * {@link ClaudeAgent#query(String, Options)}, drives one turn to completion by iterating
 * {@link Query#messages()}, then lets the process exit. State across turns is carried purely by
 * the CLI's own session file (resume by {@code sessionId}); nothing is kept alive in the JVM.
 *
 * <p>Trade-off: dead simple and crash-isolated (each turn is independent), but you pay the full
 * subprocess spawn + model init cost on every turn (single-digit seconds). When that latency
 * matters, keep the process alive instead — see {@code SessionPoolExample}.
 *
 * <p>This example is distilled from a real service's "legacy" execution path and desensitized:
 * the business tool/sandbox/storage wiring is replaced with a trivial in-process MCP tool and a
 * fixed system prompt. The structural pieces that matter in production are kept:
 * <ul>
 *   <li>streaming partial text to a callback ({@code includePartialMessages})</li>
 *   <li>hermetic isolation so the subprocess ignores the host's MCP/skills/settings</li>
 *   <li>a wall-clock timeout guard that interrupts the run</li>
 *   <li>a {@code canUseTool} permission callback and a {@code PreToolUse} hook</li>
 *   <li>{@code sessionId} for the first turn vs {@code resume(sessionId)} for follow-ups</li>
 * </ul>
 *
 * <pre>{@code
 * mvn -q -DskipTests package
 * javac -cp target/classes examples/SingleTurnExample.java -d /tmp/ex
 * java  -cp target/classes:/tmp/ex SingleTurnExample
 * }</pre>
 */
public class SingleTurnExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Path to the claude CLI; null lets the SDK find it on PATH. */
    private final String claudePath;
    private final String model;
    private final int timeoutSeconds;

    public SingleTurnExample(String claudePath, String model, int timeoutSeconds) {
        this.claudePath = claudePath;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Callback for streamed output. In a real app this pushes tokens to SSE / a websocket. */
    public interface TurnCallback {
        void onToken(String text);
    }

    /**
     * Run exactly one turn in its own subprocess.
     *
     * @param sessionId stable id for this conversation (a UUID in production)
     * @param resume    false on the first turn (create the session), true to continue an existing one
     */
    public String ask(String sessionId, String userMessage, boolean resume, TurnCallback callback) {
        Options.Builder opt = Options.builder()
                .model(model)
                .thinking(ThinkingConfig.adaptive())
                .includePartialMessages(true)          // enable streamed token deltas
                .allowDangerouslySkipPermissions(true) // headless: don't block on interactive prompts
                // --- hermetic isolation -------------------------------------------------------
                // Make the subprocess depend ONLY on the in-process MCP server injected below, never
                // on whatever MCP servers / skills / settings / CLAUDE.md happen to sit on the host
                // (e.g. ~/.claude.json user-scope MCP servers leak in otherwise).
                .settings("{}")
                .settingSources()        // load nothing from disk
                .strictMcpConfig(true)   // ignore external .mcp.json / enterprise / ~/.claude.json MCP
                .disallowedTools("Edit", "Write", "WebFetch") // strip built-ins this example shouldn't use
                .allowedTools("mcp__demo__echo", "Bash", "Grep", "Glob")
                .mcpServer(demoMcpServer())
                // A PreToolUse hook is the right place for command policy (here: block `rm`).
                .hook(HookEvent.PRE_TOOL_USE, "Bash", (input, toolUseId) -> {
                    String cmd = text(input, "command");
                    if (cmd != null && cmd.trim().startsWith("rm")) {
                        return HookOutput.block("destructive command blocked by policy");
                    }
                    return HookOutput.cont();
                })
                // canUseTool is the per-call permission gate (parallel to the hook above; shown for completeness).
                .canUseTool((toolName, toolInput, ctx) -> PermissionResult.allow())
                // Don't let the parent process's auth env bleed into the child unintentionally.
                .unsetEnv("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL");

        if (claudePath != null && !claudePath.isBlank()) {
            opt.pathToClaudeCodeExecutable(claudePath);
        }

        String systemPrompt = "You are a concise demo assistant. Answer in one short sentence.";
        if (resume) {
            // resume re-attaches to the CLI session file; a fresh systemPrompt still overrides the
            // one frozen in the session, so dynamic context stays current each turn.
            opt.resume(sessionId).systemPrompt(systemPrompt);
        } else {
            opt.sessionId(sessionId).systemPrompt(systemPrompt);
        }

        // Wall-clock timeout guard: interrupt the Query (stops the CLI) and the consuming thread.
        AtomicReference<Query> queryRef = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread consumer = Thread.currentThread();
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(timeoutSeconds * 1000L);
                timedOut.set(true);
                Query q = queryRef.get();
                if (q != null) {
                    q.interrupt().exceptionally(ex -> null);
                }
                consumer.interrupt(); // unblock messages() iteration
            } catch (InterruptedException e) {
                // Expected: normal completion interrupts the guard early so it stops counting down.
                Thread.currentThread().interrupt();
            }
        }, "turn-timeout-" + sessionId);
        guard.setDaemon(true);
        guard.start();

        StringBuilder response = new StringBuilder();
        try {
            Query query = ClaudeAgent.query(userMessage, opt.build()); // <-- spawns the subprocess
            queryRef.set(query);

            for (SdkMessage msg : query.messages()) {
                if (Thread.currentThread().isInterrupted()) break;

                if (msg instanceof SdkPartialAssistantMessage partial) {
                    // streamed token: content_block_delta -> delta.text
                    if ("content_block_delta".equals(partial.eventType())) {
                        var delta = partial.event().path("delta");
                        if ("text_delta".equals(delta.path("type").asText())) {
                            callback.onToken(delta.path("text").asText());
                        }
                    }
                } else if (msg instanceof SdkAssistantMessage am) {
                    // a complete assistant block (tool calls, full text); keep the latest text.
                    response.setLength(0);
                    response.append(am.message().text());
                } else if (msg instanceof SdkResultMessage r) {
                    // terminal message of the run.
                    if (r.isError()) {
                        return timedOut.get()
                                ? "[timed out after " + timeoutSeconds + "s]"
                                : "[error: " + r.subtype() + "]";
                    }
                    if (r.result() != null) {
                        response.setLength(0);
                        response.append(r.result());
                    }
                    System.out.printf("    (turn done: cost=$%.5f turns=%s)%n",
                            r.totalCostUsd() == null ? 0.0 : r.totalCostUsd(), r.numTurns());
                    break; // process will now exit
                }
            }
        } catch (Exception e) {
            return "[exception: " + e.getMessage() + "]";
        } finally {
            guard.interrupt(); // wake the guard if the turn finished normally
        }
        return response.toString();
    }

    /** A trivial in-process MCP server with one tool, standing in for real business tools. */
    private static SdkMcpServer demoMcpServer() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("text").put("type", "string");
        schema.putArray("required").add("text");

        return ClaudeAgent.createSdkMcpServer("demo",
                ClaudeAgent.tool("echo", "Echo back the given text", schema,
                        args -> McpToolResult.text("echo: " + args.path("text").asText())));
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static void main(String[] args) {
        // claudePath=null -> resolve "claude" on PATH. Adjust the model to one your CLI auth allows.
        SingleTurnExample ex = new SingleTurnExample(null, "claude-opus-4-7", 120);
        String sessionId = java.util.UUID.randomUUID().toString();

        System.out.println("Turn 1 (fresh session, new process):");
        System.out.print("    ");
        String a1 = ex.ask(sessionId, "My favorite number is 7. Remember it.", false, System.out::print);
        System.out.println("\n  => " + a1);

        System.out.println("\nTurn 2 (resume, ANOTHER new process — state comes from the CLI session file):");
        System.out.print("    ");
        String a2 = ex.ask(sessionId, "What is my favorite number?", true, System.out::print);
        System.out.println("\n  => " + a2);
    }
}
