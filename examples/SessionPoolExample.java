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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-reuse pool — keep one {@code claude} CLI subprocess alive per session and feed it many
 * turns over its stdin, instead of spawning a fresh process per turn.
 *
 * <p>Why: each spawn pays the subprocess + model init cost (single-digit seconds). For an
 * interactive assistant that cost dominates short turns. By holding the {@link Query} open and
 * driving follow-ups with {@link Query#streamInput(String)}, only the first turn of a session pays
 * spawn; later turns reuse the warm process.
 *
 * <p>Distilled and desensitized from a production pool. The non-obvious mechanics that make reuse
 * correct — and that you must get right yourself — are preserved:
 * <ul>
 *   <li><b>Subscribe ONCE per process</b> (a streaming Query is a single {@code Flow.Publisher});
 *       a daemon subscriber drains messages into a queue for the whole process lifetime.</li>
 *   <li><b>Epoch tagging</b>: every queued message carries the turn number it belongs to, so a late
 *       message from a previous turn (e.g. arriving after a timeout) is discarded, not mis-attributed
 *       to the current turn.</li>
 *   <li><b>Freshness check</b>: if the inputs that were baked into the process at spawn time (model,
 *       tool scope, system-prompt context) have since changed, the cached process is stale — close
 *       it and respawn rather than answer with a frozen view.</li>
 *   <li><b>Abnormal close</b>: on timeout / process death / a turn-level error, kill the process and
 *       drop it from the pool; never reuse a process in an unknown state.</li>
 *   <li><b>Capacity gate via CAS</b>: bound the number of live processes; a per-key map lock can't
 *       enforce a global cap, so admission is a separate atomic counter.</li>
 * </ul>
 *
 * <p>Single-process, in-memory pool (no cross-node coordination). In a multi-node deployment you'd
 * still serialize turns per session (so one session never hits two nodes at once) and re-validate
 * freshness against shared state; the worst case of a duplicate process is just "paid spawn twice".
 *
 * <pre>{@code
 * mvn -q -DskipTests package
 * javac -cp target/classes examples/SessionPoolExample.java -d /tmp/ex
 * java  -cp target/classes:/tmp/ex SessionPoolExample
 * }</pre>
 */
public class SessionPoolExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String claudePath;
    private final String model;
    private final int maxPoolSize;
    private final int turnTimeoutSeconds;

    public SessionPoolExample(String claudePath, String model, int maxPoolSize, int turnTimeoutSeconds) {
        this.claudePath = claudePath;
        this.model = model;
        this.maxPoolSize = maxPoolSize;
        this.turnTimeoutSeconds = turnTimeoutSeconds;
    }

    // ---- pool state ---------------------------------------------------------------------------

    private final ConcurrentHashMap<String, ActiveSession> sessions = new ConcurrentHashMap<>();
    /** Admission counter — the real global capacity gate (see class doc). */
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicLong spawnTotal = new AtomicLong();
    private final AtomicLong reuseTotal = new AtomicLong();

    /** A message tagged with the turn (epoch) that produced it. */
    private record EpochMsg(long epoch, SdkMessage msg, boolean death) {
        static EpochMsg of(long epoch, SdkMessage m) { return new EpochMsg(epoch, m, false); }
        static EpochMsg death(long epoch) { return new EpochMsg(epoch, null, true); }
    }

    /** One live subprocess + the per-process state needed to drive turns through it. */
    private static final class ActiveSession {
        final String sessionId;
        final Query query;
        final LinkedBlockingQueue<EpochMsg> queue = new LinkedBlockingQueue<>();
        final AtomicLong turnEpoch = new AtomicLong();
        /** Snapshot of the inputs baked in at spawn time; a change means the process is stale. */
        final String scopeSnapshot;
        volatile boolean dead = false;
        volatile Throwable deadCause;

        ActiveSession(String sessionId, Query query, String scopeSnapshot) {
            this.sessionId = sessionId;
            this.query = query;
            this.scopeSnapshot = scopeSnapshot;
        }
    }

    enum CloseReason { TIMEOUT, DEAD, ERROR, STALE, SHUTDOWN }

    static final class PoolFullException extends RuntimeException {
        PoolFullException(String m) { super(m); }
    }

    // ---- public entry point -------------------------------------------------------------------

    /**
     * Run one turn for {@code sessionId}, reusing a warm process when possible.
     *
     * @param scope an opaque snapshot of everything baked into the process at spawn (model, tool
     *              scope, user context, ...). When it changes, the cached process is respawned.
     */
    public String ask(String sessionId, String userMessage, String scope, SingleTurnExample.TurnCallback cb) {
        ActiveSession sess = getOrSpawn(sessionId, scope);
        if (sess == null) {
            // pool full / spawn failed — a real app falls back to the per-turn path here.
            return "[no capacity: fall back to SingleTurnExample]";
        }
        return runTurn(sess, userMessage, cb);
    }

    // ---- spawn / reuse ------------------------------------------------------------------------

    private ActiveSession getOrSpawn(String sessionId, String scope) {
        ActiveSession existing = sessions.get(sessionId);
        if (existing != null) {
            boolean stale = !Objects.equals(existing.scopeSnapshot, scope);
            if (existing.dead || stale) {
                // Cached process can't serve this turn correctly — close and rebuild in the same slot.
                close(existing, existing.dead ? CloseReason.DEAD : CloseReason.STALE);
                sessions.remove(sessionId, existing);
            } else {
                reuseTotal.incrementAndGet();
                return existing; // warm hit
            }
        }

        // New spawn needs an admission slot (global cap). CAS so two different sessions racing to
        // spawn can't both pass a size check and overshoot maxPoolSize.
        int n = admitted.incrementAndGet();
        if (n > maxPoolSize) {
            admitted.decrementAndGet();
            return null; // caller falls back to per-turn
        }
        try {
            ActiveSession sess = spawn(sessionId, scope);
            sessions.put(sessionId, sess);
            spawnTotal.incrementAndGet();
            return sess;
        } catch (Exception e) {
            admitted.decrementAndGet(); // release the slot we reserved
            System.err.println("spawn failed sessionId=" + sessionId + ": " + e.getMessage());
            return null;
        }
    }

    private ActiveSession spawn(String sessionId, String scope) {
        String systemPrompt = "You are a concise demo assistant. Answer in one short sentence. "
                + "Conversation scope: " + scope;

        Options.Builder opt = Options.builder()
                .model(model)
                .thinking(ThinkingConfig.adaptive())
                .includePartialMessages(true)
                .allowDangerouslySkipPermissions(true)
                // hermetic isolation (see SingleTurnExample for the full rationale)
                .settings("{}")
                .settingSources()
                .strictMcpConfig(true)
                .disallowedTools("Edit", "Write", "WebFetch")
                .allowedTools("mcp__demo__echo", "Bash", "Grep", "Glob")
                .mcpServer(demoMcpServer())
                .hook(HookEvent.PRE_TOOL_USE, "Bash", (input, toolUseId) -> {
                    var cmd = input == null ? null : input.get("command");
                    if (cmd != null && cmd.asText().trim().startsWith("rm")) {
                        return HookOutput.block("destructive command blocked by policy");
                    }
                    return HookOutput.cont();
                })
                .sessionId(sessionId)
                .systemPrompt(systemPrompt)
                .unsetEnv("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL");
        if (claudePath != null && !claudePath.isBlank()) {
            opt.pathToClaudeCodeExecutable(claudePath);
        }

        Query query = ClaudeAgent.streamingQuery(opt.build()); // keeps the process alive across turns
        ActiveSession sess = new ActiveSession(sessionId, query, scope);

        // Subscribe ONCE for the whole process lifetime. Every message is tagged with the current
        // epoch so the consumer can drop late messages from a previous turn.
        query.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) { this.sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(SdkMessage m) { sess.queue.offer(EpochMsg.of(sess.turnEpoch.get(), m)); }
            @Override public void onError(Throwable t) {
                sess.deadCause = t; sess.dead = true;
                sess.queue.offer(EpochMsg.death(sess.turnEpoch.get()));
            }
            @Override public void onComplete() {
                sess.dead = true;
                sess.queue.offer(EpochMsg.death(sess.turnEpoch.get()));
            }
        });

        // Wait for init, then drop any messages emitted during init so they don't pollute turn 1.
        // clear() BEFORE the dead-check: if the process died during init, the daemon already queued
        // a death signal that clear() would wipe — re-check dead afterwards and fail loudly.
        try {
            query.initializationResult().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("init timeout/failed sessionId=" + sessionId + ": " + e.getMessage());
        }
        sess.queue.clear();
        if (sess.dead) {
            String cause = sess.deadCause != null ? sess.deadCause.getMessage() : "process died during init";
            query.close();
            throw new RuntimeException("session dead after init: " + cause, sess.deadCause);
        }
        return sess;
    }

    // ---- one turn over a warm process ---------------------------------------------------------

    private String runTurn(ActiveSession sess, String userMessage, SingleTurnExample.TurnCallback cb) {
        long epoch = sess.turnEpoch.incrementAndGet(); // claim this turn's epoch
        long deadline = System.currentTimeMillis() + turnTimeoutSeconds * 1000L;

        try {
            sess.query.streamInput(userMessage); // feed the next turn over stdin
        } catch (Exception e) {
            close(sess, CloseReason.DEAD);
            sessions.remove(sess.sessionId, sess);
            return "[streamInput failed: " + e.getMessage() + "]";
        }

        StringBuilder response = new StringBuilder();
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                close(sess, CloseReason.TIMEOUT);          // never reuse a process stuck mid-turn
                sessions.remove(sess.sessionId, sess);
                return "[timed out after " + turnTimeoutSeconds + "s]";
            }

            EpochMsg em;
            try {
                em = sess.queue.poll(remain, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close(sess, CloseReason.ERROR);
                sessions.remove(sess.sessionId, sess);
                return "[interrupted]";
            }
            if (em == null) continue; // timeout slice; re-check deadline

            if (em.death()) {
                close(sess, CloseReason.DEAD);
                sessions.remove(sess.sessionId, sess);
                return "[process died: "
                        + (sess.deadCause != null ? sess.deadCause.getMessage() : "unknown") + "]";
            }
            if (em.epoch() != epoch) continue; // stale message from a previous turn — drop it

            SdkMessage msg = em.msg();
            if (msg instanceof SdkPartialAssistantMessage partial) {
                if ("content_block_delta".equals(partial.eventType())) {
                    var delta = partial.event().path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        cb.onToken(delta.path("text").asText());
                    }
                }
            } else if (msg instanceof SdkAssistantMessage am) {
                response.setLength(0);
                response.append(am.message().text());
            } else if (msg instanceof SdkResultMessage r) {
                if (r.isError()) {
                    // A turn-level error leaves the process in an unknown state — drop it.
                    close(sess, CloseReason.ERROR);
                    sessions.remove(sess.sessionId, sess);
                    return "[error: " + r.subtype() + "]";
                }
                if (r.result() != null) {
                    response.setLength(0);
                    response.append(r.result());
                }
                System.out.printf("    (turn done: cost=$%.5f turns=%s — process KEPT warm)%n",
                        r.totalCostUsd() == null ? 0.0 : r.totalCostUsd(), r.numTurns());
                return response.toString(); // process stays alive for the next turn
            }
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private void close(ActiveSession sess, CloseReason reason) {
        try {
            sess.query.close();
        } catch (Exception e) {
            System.err.println("close error sessionId=" + sess.sessionId + ": " + e.getMessage());
        } finally {
            admitted.decrementAndGet(); // free the admission slot
            System.out.println("    (closed sessionId=" + sess.sessionId + " reason=" + reason + ")");
        }
    }

    public void shutdown() {
        for (ActiveSession s : List.copyOf(sessions.values())) {
            close(s, CloseReason.SHUTDOWN);
            sessions.remove(s.sessionId, s);
        }
    }

    public String metrics() {
        long spawn = spawnTotal.get(), reuse = reuseTotal.get(), total = spawn + reuse;
        double rate = total == 0 ? 0 : (double) reuse / total;
        return String.format("pool_metrics live=%d spawn_total=%d reuse_total=%d reuse_rate=%.2f",
                sessions.size(), spawn, reuse, rate);
    }

    private static SdkMcpServer demoMcpServer() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        return ClaudeAgent.createSdkMcpServer("demo",
                ClaudeAgent.tool("echo", "Echo back the given text", schema,
                        args -> McpToolResult.text("echo: " + args.path("text").asText())));
    }

    public static void main(String[] args) {
        SessionPoolExample pool = new SessionPoolExample(null, "claude-opus-4-7", 16, 120);
        String sessionId = java.util.UUID.randomUUID().toString();
        String scope = "demo-scope-v1";

        try {
            System.out.println("Turn 1 (cold — spawns the process):");
            System.out.print("    ");
            String a1 = pool.ask(sessionId, "My favorite number is 7. Remember it.", scope, System.out::print);
            System.out.println("\n  => " + a1);

            System.out.println("\nTurn 2 (warm — SAME process via streamInput):");
            System.out.print("    ");
            String a2 = pool.ask(sessionId, "What is my favorite number?", scope, System.out::print);
            System.out.println("\n  => " + a2);

            System.out.println("\nTurn 3 (scope changed — process is stale, respawns):");
            System.out.print("    ");
            String a3 = pool.ask(sessionId, "Say hello.", "demo-scope-v2", System.out::print);
            System.out.println("\n  => " + a3);

            System.out.println("\n" + pool.metrics());
        } finally {
            pool.shutdown();
        }
    }
}
