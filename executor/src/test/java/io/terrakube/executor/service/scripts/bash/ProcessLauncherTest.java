package io.terrakube.executor.service.scripts.bash;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessLauncherTest {

    // A script's output is the step log once launch() completes. The script exits long before a
    // slow listener (a Redis or API write per line) has read its output, and every line must still
    // be there by then.
    @Test
    void launchCompletesOnlyAfterEveryOutputLineReachedTheListener() throws Exception {
        ExecutorService executor = Executors.newWorkStealingPool();
        try {
            List<String> lines = new CopyOnWriteArrayList<>();
            ProcessLauncher launcher = new ProcessLauncher(executor, "bash", "-c", "seq 1 5000");
            launcher.setOutputListener(line -> {
                if (lines.isEmpty()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                lines.add(line);
            });

            assertEquals(0, launcher.launch().get(30, TimeUnit.SECONDS));

            assertEquals(5000, lines.size());
            assertEquals("5000", lines.get(4999));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void destroyForciblyKillsSpawnedChildProcessesWithoutUnshare() throws Exception {
        ExecutorService executor = Executors.newWorkStealingPool();
        java.nio.file.Path childPidFile = java.nio.file.Files.createTempFile("proc-launcher-child-", ".pid");
        try {
            ProcessLauncher launcher = new ProcessLauncher(
                    executor,
                    "sh", "-c",
                    "sleep 30 & echo $! > \"" + childPidFile.toAbsolutePath() + "\"; wait"
            );
            launcher.setPidIsolation(false);
            var future = launcher.launch();

            long childPid = -1;
            long waitLimit = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < waitLimit) {
                String content = java.nio.file.Files.readString(childPidFile).trim();
                if (!content.isEmpty()) {
                    childPid = Long.parseLong(content);
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(childPid > 0, "Child sleep process PID should be recorded");

            launcher.destroyForcibly();
            future.handle((code, ex) -> null).get(5, TimeUnit.SECONDS);

            long finalChildPid = childPid;
            long goneDeadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < goneDeadline) {
                if (!isProcessStillRunning(finalChildPid)) {
                    break;
                }
                Thread.sleep(25);
            }
            assertFalse(isProcessStillRunning(childPid),
                    "Descendant child process must be killed by destroyForcibly()");
        } finally {
            java.nio.file.Files.deleteIfExists(childPidFile);
            executor.shutdownNow();
        }
    }

    private static boolean isProcessStillRunning(long pid) {
        return ProcessHandle.of(pid).map(handle -> {
            if (!handle.isAlive()) {
                return false;
            }
            // Under containers or sandboxes where PID 1 does not reap reparented orphans,
            // a killed child stays in 'Z' (zombie) state and ProcessHandle.isAlive() still returns true.
            java.nio.file.Path statPath = java.nio.file.Path.of("/proc/" + pid + "/stat");
            if (java.nio.file.Files.isReadable(statPath)) {
                try {
                    String stat = java.nio.file.Files.readString(statPath);
                    int rparen = stat.lastIndexOf(')');
                    if (rparen > 0 && rparen + 2 < stat.length() && stat.charAt(rparen + 2) == 'Z') {
                        return false;
                    }
                } catch (java.io.IOException ignored) {
                    return false;
                }
            }
            return true;
        }).orElse(false);
    }
}
