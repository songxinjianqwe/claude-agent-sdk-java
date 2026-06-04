package com.anthropic.claude.agent.agent;

import com.anthropic.claude.agent.permission.PermissionMode;
import java.util.List;

/**
 * A custom agent definition (the value type of the Node SDK's {@code agents} map / the CLI
 * {@code --agents} JSON). {@code description} and {@code prompt} are required; the rest optional.
 *
 * <p>(Agent-level {@code mcpServers} is not modeled here — see README "Known parity gaps".)
 *
 * @param description     what the agent is for (required)
 * @param prompt          the agent's system prompt (required)
 * @param model           model alias / full id / "inherit" (nullable)
 * @param tools           allowed tools for this agent (nullable)
 * @param disallowedTools tools denied for this agent (nullable)
 * @param maxTurns        per-agent turn cap (nullable)
 * @param permissionMode  per-agent permission mode (nullable)
 * @param skills          skills available to the agent (nullable)
 * @param initialPrompt   an initial prompt to seed the agent (nullable)
 * @param effort          reasoning effort: "low"/"medium"/"high"/"xhigh"/"max" or a number-as-string (nullable)
 * @param background      run this agent as a non-blocking background task (nullable)
 * @param memory          agent-memory scope: "user"/"project"/"local" (nullable)
 */
public record AgentDefinition(
        String description,
        String prompt,
        String model,
        List<String> tools,
        List<String> disallowedTools,
        Integer maxTurns,
        PermissionMode permissionMode,
        List<String> skills,
        String initialPrompt,
        String effort,
        Boolean background,
        String memory) {

    public static AgentDefinition of(String description, String prompt) {
        return new AgentDefinition(description, prompt, null, null, null, null, null, null, null,
                null, null, null);
    }

    public AgentDefinition withModel(String model) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withTools(List<String> tools) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withMaxTurns(int maxTurns) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withPermissionMode(PermissionMode permissionMode) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withSkills(List<String> skills) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withInitialPrompt(String initialPrompt) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withEffort(String effort) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withBackground(boolean background) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }

    public AgentDefinition withMemory(String memory) {
        return new AgentDefinition(description, prompt, model, tools, disallowedTools, maxTurns,
                permissionMode, skills, initialPrompt, effort, background, memory);
    }
}
