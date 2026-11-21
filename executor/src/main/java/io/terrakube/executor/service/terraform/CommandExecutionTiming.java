package io.terrakube.executor.service.terraform;

import io.terrakube.executor.service.mode.TerraformJob;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class CommandExecutionTiming {
    @FunctionalInterface
    interface Command<T> {
        CompletableFuture<T> start() throws IOException;
    }

    static <T> T execute(TerraformJob job, String phase, Command<T> command)
            throws IOException, ExecutionException, InterruptedException {
        return execute(job, phase, command, System::nanoTime);
    }

    static <T> T execute(TerraformJob job, String phase, Command<T> command, LongSupplier clock)
            throws IOException, ExecutionException, InterruptedException {
        long start = clock.getAsLong();
        // The client starts the process synchronously. A launch failure throws instead of returning a future.
        CompletableFuture<T> execution = command.start();
        boolean finished = true;
        try {
            return execution.get();
        } catch (InterruptedException exception) {
            finished = false;
            throw exception;
        } finally {
            if (finished) {
                job.setExecutionDurationMs(TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - start));
                job.setExecutionPhase(phase);
            }
        }
    }

    private CommandExecutionTiming() {
    }
}
