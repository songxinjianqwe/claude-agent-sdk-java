package com.anthropic.claude.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.anthropic.claude.agent.message.SdkAssistantMessage;
import com.anthropic.claude.agent.message.SdkMessage;
import com.anthropic.claude.agent.message.SdkResultMessage;
import com.anthropic.claude.agent.message.SdkSystemMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/**
 * Real end-to-end smoke against the installed {@code claude} CLI. Skipped unless
 * {@code -Dclaude.sdk.it=true} is set (requires a logged-in claude and consumes quota).
 *
 * <pre>{@code mvn test -Dtest=RealClaudeSmokeTest -Dclaude.sdk.it=true}</pre>
 */
public class RealClaudeSmokeTest {

    @Test
    public void oneShotAgainstRealClaude() throws Exception {
        Assume.assumeTrue("set -Dclaude.sdk.it=true to run the real-claude smoke test",
                Boolean.getBoolean("claude.sdk.it"));

        // Run in a clean temp dir so we don't load a project's large CLAUDE.md.
        Path cwd = Files.createTempDirectory("claude-sdk-it");
        Query q = ClaudeAgent.query("Reply with exactly the single word: pong",
                Options.builder().cwd(cwd.toString()).model("haiku").build());

        List<SdkMessage> msgs = q.collect();

        assertTrue("should see a system init", msgs.stream()
                .anyMatch(m -> m instanceof SdkSystemMessage s && s.isInit()));
        assertTrue("should see an assistant message",
                msgs.stream().anyMatch(m -> m instanceof SdkAssistantMessage));

        SdkResultMessage result = null;
        for (SdkMessage m : msgs) {
            if (m instanceof SdkResultMessage r) {
                result = r;
            }
        }
        assertNotNull("should end with a result", result);
        assertFalse("result should not be an error", result.isError());
        assertNotNull("result text present", result.result());
    }
}
