package com.anthropic.claude.agent.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code type: "result"} — the terminal message of a run. Mirrors the Node SDK
 * {@code SDKResultMessage}. On {@link Subtype#SUCCESS} {@link #result()} holds the final text; on
 * error subtypes {@code result} is null and {@link #isError()} is true.
 *
 * @param subtype       success / error_* (see {@link Subtype})
 * @param isError       whether this is an error result
 * @param result        final assistant text on success, else null
 * @param sessionId     session id
 * @param uuid          message uuid
 * @param numTurns      number of turns taken (nullable)
 * @param durationMs    wall-clock duration (nullable)
 * @param durationApiMs API duration (nullable)
 * @param totalCostUsd  total cost in USD (nullable)
 * @param stopReason    final stop reason (nullable)
 * @param usage         raw aggregate {@code usage} node (nullable)
 * @param modelUsage    raw per-model usage node (nullable)
 * @param raw           original JSON (includes permission_denials, modelUsage, ttft_ms, ...)
 */
public record SdkResultMessage(
        Subtype subtype,
        boolean isError,
        String result,
        String sessionId,
        String uuid,
        Integer numTurns,
        Long durationMs,
        Long durationApiMs,
        Double totalCostUsd,
        String stopReason,
        JsonNode usage,
        JsonNode modelUsage,
        JsonNode raw) implements SdkMessage {

    @Override
    public String type() {
        return "result";
    }

    /** Structured output (when {@code Options.jsonSchema} was set), or null. From {@link #raw()}. */
    public JsonNode structuredOutput() {
        JsonNode n = raw.get("structured_output");
        return (n == null || n.isNull()) ? null : n;
    }

    /** Result subtypes emitted by the CLI ({@code result.subtype}). */
    public enum Subtype {
        SUCCESS("success"),
        ERROR_DURING_EXECUTION("error_during_execution"),
        ERROR_MAX_TURNS("error_max_turns"),
        ERROR_MAX_BUDGET_USD("error_max_budget_usd"),
        ERROR_MAX_STRUCTURED_OUTPUT_RETRIES("error_max_structured_output_retries"),
        /** Any subtype not recognized by this SDK version (drift-tolerant). */
        UNKNOWN(null);

        private final String wire;

        Subtype(String wire) {
            this.wire = wire;
        }

        /** The on-the-wire string for this subtype (null for {@link #UNKNOWN}). */
        public String wire() {
            return wire;
        }

        /** Map a wire string to a subtype; unrecognized values map to {@link #UNKNOWN}. */
        public static Subtype fromWire(String s) {
            if (s != null) {
                for (Subtype v : values()) {
                    if (s.equals(v.wire)) {
                        return v;
                    }
                }
            }
            return UNKNOWN;
        }
    }
}
