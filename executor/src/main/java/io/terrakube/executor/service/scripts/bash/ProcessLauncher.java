package io.terrakube.executor.service.scripts.bash;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public final class ProcessLauncher {
    private static final List<String> PID_ISOLATION_PREFIX = detectPidIsolationPrefix();

    private Process process;
    private ProcessBuilder builder;
    private Consumer<String> outputListener, errorListener;
    private boolean inheritIO;
    private boolean pidIsolation = true;
    private ExecutorService executor;

    private static List<String> detectPidIsolationPrefix() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return Collections.emptyList();
        }
        // First try unprivileged user namespace unshare (standard unprivileged Linux container)
        if (canExecute("unshare", "--map-root-user", "--user", "--pid", "--fork", "--mount-proc", "true")) {
            return List.of("unshare", "--map-root-user", "--user", "--pid", "--fork", "--mount-proc");
        }
        // Fallback: root or privileged container without --user
        if (canExecute("unshare", "--pid", "--fork", "--mount-proc", "true")) {
            return List.of("unshare", "--pid", "--fork", "--mount-proc");
        }
        log.warn("PID namespace isolation unavailable (unshare missing or insufficient privileges) - "
                + "custom script child processes will not be isolated from this JVM's PID namespace");
        return Collections.emptyList();
    }

    private static boolean canExecute(String... command) {
        try {
            Process p = new ProcessBuilder(command).start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

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

    public void clearEnvironment() {
        assert this.process == null;
        this.builder.environment().clear();
    }

    void appendCommands(String... commands) {
        Stream<String> filteredCommands = Arrays.stream(commands).filter(c -> c != null && c.length() > 0);
        this.builder.command().addAll(filteredCommands.collect(Collectors.toList()));
    }

    public void setEnvironmentVariable(String name, String value) {
        assert name != null && name.length() > 0;
        Map<String, String> env = this.builder.environment();
        value = (value != null ? env.put(name, value) : env.remove(name));
    }

    public void setOrAppendEnvironmentVariable(String name, String value, String delimiter) {
        assert name != null && name.length() > 0;
        if (value != null && value.length() > 0) {
            String current = System.getenv(name);
            String target = (current == null || current.length() == 0 ? value : String.join(delimiter, current, value));
            this.setEnvironmentVariable(name, target);
        }
    }

    public void setPidIsolation(boolean enabled) {
        assert this.process == null;
        this.pidIsolation = enabled;
    }

    public boolean isPidIsolationActive() {
        return this.pidIsolation && !PID_ISOLATION_PREFIX.isEmpty();
    }

    public CompletableFuture<Integer> launch() {
        assert this.process == null;
        if (this.inheritIO) {
            this.builder.inheritIO();
        }
        if (this.pidIsolation && !PID_ISOLATION_PREFIX.isEmpty()) {
            List<String> originalCommands = new ArrayList<>(this.builder.command());
            List<String> isolatedCommands = new ArrayList<>(PID_ISOLATION_PREFIX);
            isolatedCommands.addAll(originalCommands);
            this.builder.command(isolatedCommands);
        }
        try {
            this.process = this.builder.start();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        CompletableFuture<Void> outputFuture = (this.outputListener != null && !this.inheritIO)
                ? CompletableFuture.runAsync(() -> this.readProcessStream(this.process.getInputStream(), this.outputListener), this.executor)
                : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> errorFuture = (this.errorListener != null && !this.inheritIO)
                ? CompletableFuture.runAsync(() -> this.readProcessStream(this.process.getErrorStream(), this.errorListener), this.executor)
                : CompletableFuture.completedFuture(null);

        return CompletableFuture.supplyAsync(() -> {
            try {
                int exitCode = this.process.waitFor();
                outputFuture.join();
                errorFuture.join();
                return exitCode;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }, this.executor);
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
