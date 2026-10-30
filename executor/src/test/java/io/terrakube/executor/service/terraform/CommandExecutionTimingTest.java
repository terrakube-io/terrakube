package io.terrakube.executor.service.terraform;

import io.terrakube.executor.service.mode.TerraformJob;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CommandExecutionTimingTest {
    @Test
    void recordsOnlyCommandTimeWithAMonotonicClock() throws Exception {
        TerraformJob job = new TerraformJob();
        AtomicLong clock = new AtomicLong(900_000_000_000L); // Setup and approval time precede this call.
        int exitCode = CommandExecutionTiming.execute(job, "plan", () -> {
            clock.addAndGet(30_000_000_000L);
            return CompletableFuture.completedFuture(2);
        }, clock::get);
        clock.addAndGet(600_000_000_000L); // Post hooks and approval do not change the recorded time.
        assertEquals(2, exitCode);
        assertEquals(30_000L, job.getExecutionDurationMs());
        assertEquals("plan", job.getExecutionPhase());
    }

    @Test
    void recordsFailedCommandsAndExceptionalCompletion() throws Exception {
        for (boolean exceptional : new boolean[]{false, true}) {
            TerraformJob job = new TerraformJob();
            AtomicLong clock = new AtomicLong();
            CommandExecutionTiming.Command<Boolean> command = () -> {
                clock.set(120_000_000_000L);
                return exceptional
                        ? CompletableFuture.failedFuture(new IllegalStateException("command failed"))
                        : CompletableFuture.completedFuture(false);
            };
            if (exceptional) {
                assertThrows(ExecutionException.class,
                        () -> CommandExecutionTiming.execute(job, "apply", command, clock::get));
            } else {
                assertFalse(CommandExecutionTiming.execute(job, "apply", command, clock::get));
            }
            assertEquals(120_000L, job.getExecutionDurationMs());
            assertEquals("apply", job.getExecutionPhase());
        }
    }

    @Test
    void doesNotInventADurationWhenTheCommandCannotStart() {
        TerraformJob job = new TerraformJob();
        assertThrows(IOException.class, () -> CommandExecutionTiming.execute(job, "plan", () -> {
            throw new IOException("binary missing");
        }));
        assertNull(job.getExecutionDurationMs());
        assertNull(job.getExecutionPhase());
    }
}
