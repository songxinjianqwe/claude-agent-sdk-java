# Examples

Two runnable, self-contained examples showing the two ways to drive the `claude` CLI from a
long-lived service. Both are desensitized distillations of a real production agent runtime — all
business wiring (storage, DB, domain tools) is stripped down to a trivial in-process MCP tool and a
fixed system prompt, leaving only the SDK-usage skeleton.

| File | Pattern | When to use |
|------|---------|-------------|
| [`SingleTurnExample.java`](SingleTurnExample.java) | **No process reuse** — one subprocess per turn (`ClaudeAgent.query`), state carried by the CLI session file (`resume`) | Simplest, crash-isolated. Fine when turn latency doesn't matter. |
| [`SessionPoolExample.java`](SessionPoolExample.java) | **Process-reuse pool** — keep the subprocess alive (`ClaudeAgent.streamingQuery`) and feed turns via `streamInput` | Interactive assistants where the per-turn spawn cost (single-digit seconds) dominates. |

Both share the same production-shaped concerns: streamed token output (`includePartialMessages`),
hermetic isolation (`strictMcpConfig` + empty `settingSources`), a `PreToolUse` Bash policy hook, a
wall-clock timeout, and `sessionId` vs `resume`.

The pool example additionally demonstrates the mechanics that make reuse *correct*: subscribe once
per process, epoch-tag queued messages so late messages from a prior turn are dropped, respawn when
the spawn-time inputs go stale, close-on-anomaly, and a CAS admission gate for global capacity.

## Run

```bash
# from the repo root — build the SDK so target/classes exists
mvn -q -DskipTests package

# put jackson (the SDK's one dependency) on the classpath
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
CP="target/classes:$(cat /tmp/cp.txt)"

javac -cp "$CP" examples/*.java -d /tmp/ex
java  -cp "$CP:/tmp/ex" SingleTurnExample
java  -cp "$CP:/tmp/ex" SessionPoolExample
```

Requires the `claude` CLI on `PATH` (or set `pathToClaudeCodeExecutable`) and whatever auth the CLI
already uses. Adjust the `model` in each `main(...)` to one your CLI auth allows.
