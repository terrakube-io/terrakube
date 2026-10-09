package io.terrakube.executor.service.scripts.bash;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public final class ProcessLauncher {
    // Same bound terraform-client uses for its own process streams.
    private static final long STREAM_DRAIN_TIMEOUT_SECONDS = 60;

    private Process process;
    private ProcessBuilder builder;
    private Consumer<String> outputListener, errorListener;
    private boolean inheritIO;
    private ExecutorService executor;

    public ProcessLauncher(ExecutorService executor, String... commands) {
        assert executor != null;
        this.executor = executor;
        this.process = null;
        this.builder = new ProcessBuilder(commands);
    }

    public void setOutputListener(Consumer<String> listener) {
        assert this.process == null;
        this.outputListener = listener;
    }

    public void setErrorListener(Consumer<String> listener) {
        assert this.process == null;
        this.errorListener = listener;
    }

    public void setInheritIO(boolean inheritIO) {
        assert this.process == null;
        this.inheritIO = inheritIO;
    }

    public void setDirectory(File directory) {
        assert this.process == null;
        this.builder.directory(directory);
    }

    void appendCommands(String... commands) {
        Stream<String> filteredCommands = Arrays.stream(commands).filter(c -> c != null && c.length() > 0);
        this.builder.command().addAll(filteredCommands.collect(Collectors.toList()));
    }

    void setEnvironmentVariable(String name, String value) {
        assert name != null && name.length() > 0;
        Map<String, String> env = this.builder.environment();
        value = (value != null ? env.put(name, value) : env.remove(name));
    }

    void setOrAppendEnvironmentVariable(String name, String value, String delimiter) {
        assert name != null && name.length() > 0;
        if (value != null && value.length() > 0) {
            String current = System.getenv(name);
            String target = (current == null || current.length() == 0 ? value : String.join(delimiter, current, value));
            this.setEnvironmentVariable(name, target);
        }
    }

    public CompletableFuture<Integer> launch() {
        assert this.process == null;
        if (this.inheritIO) {
            this.builder.inheritIO();
        }
        try {
            this.process = this.builder.start();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        Future<Boolean> outputReader = null;
        Future<Boolean> errorReader = null;
        if (!this.inheritIO) {
            if (this.outputListener != null) {
                outputReader = this.executor.submit(() -> this.readProcessStream(this.process.getInputStream(), this.outputListener));
            }
            if (this.errorListener != null) {
                errorReader = this.executor.submit(() -> this.readProcessStream(this.process.getErrorStream(), this.errorListener));
            }
        }
        Future<Boolean> stdoutReader = outputReader;
        Future<Boolean> stderrReader = errorReader;
        // The process exiting doesn't mean its output has been read: complete only once both
        // readers have handed every line to their listener, so callers never see partial output.
        return CompletableFuture.supplyAsync(() -> {
            try {
                int exitCode = this.process.waitFor();
                awaitStreamDrained(stdoutReader, "output");
                awaitStreamDrained(stderrReader, "error");
                return exitCode;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }, this.executor);
    }

    // A background child still holding the stream open must not hang the step: log and move on.
    private void awaitStreamDrained(Future<Boolean> reader, String stream) throws InterruptedException {
        if (reader == null) {
            return;
        }
        try {
            reader.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("Script {} stream was not fully read: {}", stream, ex.toString());
        }
    }

    private boolean readProcessStream(InputStream stream, Consumer<String> listener) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.accept(line);
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
