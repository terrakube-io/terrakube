package io.terrakube.executor.service.scripts.bash;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessLauncherSecurityTest {

    @Test
    void testClearEnvironmentRemovesHostSecrets() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(Executors.newSingleThreadExecutor(), "sh", "-c", "env");
        launcher.clearEnvironment();
        launcher.setEnvironmentVariable("SAFE_VAR", "safe_value");

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);

        int exitCode = launcher.launch().get();
        org.junit.jupiter.api.Assertions.assertEquals(0, exitCode);

        String combinedOutput = String.join("\n", output);
        assertTrue(combinedOutput.contains("SAFE_VAR=safe_value"), "SAFE_VAR should be present in process environment");
        assertFalse(combinedOutput.contains("InternalSecret="), "Host secrets must not be inherited");
    }

    @Test
    void testChildProcessCannotAccessParentEnvironViaProcfs() throws Exception {
        // Exploit payload from TK-01 review (round 2 procfs bypass)
        ProcessLauncher launcher = new ProcessLauncher(
                Executors.newSingleThreadExecutor(),
                "sh", "-c",
                "ppid=$(cut -d' ' -f4 /proc/$$/stat 2>/dev/null || echo 0); " +
                "if [ \"$ppid\" != \"0\" ] && [ -f /proc/$ppid/environ ]; then " +
                "  echo \"LEAK_FOUND: $(cat /proc/$ppid/environ 2>/dev/null | tr '\\0' '\\n' | grep InternalSecret || true)\"; " +
                "else " +
                "  echo \"PROCFS_ISOLATION_PASSED: no parent environ visible\"; " +
                "fi"
        );
        launcher.clearEnvironment();

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);

        int exitCode = launcher.launch().get();
        org.junit.jupiter.api.Assertions.assertEquals(0, exitCode);

        String combinedOutput = String.join("\n", output);
        Assumptions.assumeTrue(launcher.isPidIsolationActive(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");
        assertTrue(combinedOutput.contains("PROCFS_ISOLATION_PASSED"), "Parent process environ must not be accessible via procfs");
        assertFalse(combinedOutput.contains("LEAK_FOUND: InternalSecret="), "Host secret must not be leaked via procfs");
    }
}
