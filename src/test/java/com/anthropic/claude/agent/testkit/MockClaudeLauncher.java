package com.anthropic.claude.agent.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Installs an executable shell wrapper that launches {@link MockClaude} as a real
 * subprocess, so it can be handed to the transport as {@code pathToClaudeCodeExecutable}.
 *
 * <p>The wrapper re-uses the current JVM ({@code java.home}) and test classpath
 * ({@code java.class.path}) so no extra build step is needed. Cross-platform for
 * macOS/Linux (POSIX {@code sh}); the project does not target Windows.
 */
public final class MockClaudeLauncher {

    private MockClaudeLauncher() {
    }

    /** Write the wrapper script into {@code dir} and return its path (chmod +x'd). */
    public static Path install(Path dir) throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        // exec so signals (e.g. destroyForcibly) hit the JVM directly, not an intermediate sh.
        String script = "#!/bin/sh\n"
                + "exec \"" + javaBin + "\" -cp \"" + classpath + "\" "
                + MockClaude.class.getName() + " \"$@\"\n";

        Path wrapper = Files.createTempFile(dir, "mock-claude-", ".sh");
        Files.writeString(wrapper, script, StandardCharsets.UTF_8);

        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(wrapper, perms);

        return wrapper;
    }
}
