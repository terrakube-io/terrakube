package io.terrakube.executor.service.scripts.bash;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
