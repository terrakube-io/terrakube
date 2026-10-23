package io.terrakube.executor.service.logs;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Collects the whole console output of one step so it can be saved as a single object when the step
 * ends. Up to {@code memoryLimitBytes} stays in memory. Past that the output moves to a temporary
 * file, so a step that prints hundreds of megabytes does not need that much heap, and the final
 * upload reads from the file instead of building one large String (and then a second copy of it).
 * The full output is always kept, nothing is cut here.
 *
 * Lines are written from the process output reader thread and read once, after the step ended, so
 * the methods that touch the data are synchronized rather than lock-free.
 */
@Slf4j
public class StepOutputBuffer implements AutoCloseable {

    public static final long DEFAULT_MEMORY_LIMIT_BYTES = 4L * 1024 * 1024;

    private final long memoryLimitBytes;
    private final Path spoolDirectory;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private Path file;
    private OutputStream fileOut;
    private long bytes;
    private boolean closed;

    /**
     * A buffer that spills next to {@code workingDirectory} (the executor's scratch disk, the same place
     * the plan artifacts are staged), not in the JVM temp directory, which can be memory backed.
     */
    public static StepOutputBuffer forStep(long memoryLimitBytes, File workingDirectory) {
        Path spool = null;
        File parent = workingDirectory == null ? null : workingDirectory.getAbsoluteFile().getParentFile();
        if (parent != null && parent.isDirectory()) {
            spool = parent.toPath();
        }
        return new StepOutputBuffer(memoryLimitBytes, spool);
    }

    /** A buffer that holds only a short message, such as the reason a step could not run. */
    public static StepOutputBuffer ofMessage(String message) {
        StepOutputBuffer buffer = new StepOutputBuffer();
        buffer.append(message);
        return buffer;
    }

    public StepOutputBuffer() {
        this(DEFAULT_MEMORY_LIMIT_BYTES, null);
    }

    public StepOutputBuffer(long memoryLimitBytes, Path spoolDirectory) {
        this.memoryLimitBytes = memoryLimitBytes;
        this.spoolDirectory = spoolDirectory;
    }

    public synchronized void appendln(String line) {
        append(line == null ? "" : line);
        append("\n");
    }

    public synchronized void append(String text) {
        if (closed || text == null) {
            return;
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (fileOut == null && memoryLimitBytes > 0 && bytes + data.length > memoryLimitBytes) {
                moveToFile();
            }
            if (fileOut != null) {
                fileOut.write(data);
            } else {
                memory.write(data, 0, data.length);
            }
            bytes += data.length;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to buffer step output", e);
        }
    }

    private void moveToFile() throws IOException {
        file = spoolDirectory != null
                ? Files.createTempFile(spoolDirectory, "step-output-", ".log")
                : Files.createTempFile("step-output-", ".log");
        fileOut = Files.newOutputStream(file, StandardOpenOption.WRITE);
        memory.writeTo(fileOut);
        memory = null;
        log.info("Step output is larger than {} bytes, keeping it in {} instead of memory", memoryLimitBytes, file);
    }

    public synchronized long sizeInBytes() {
        return bytes;
    }

    public synchronized boolean isInMemory() {
        return file == null;
    }

    /** Copies everything collected so far to {@code target}. */
    public synchronized void writeTo(OutputStream target) throws IOException {
        if (file == null) {
            if (memory != null) {
                memory.writeTo(target);
            }
            return;
        }
        fileOut.flush();
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(target);
        }
    }

    /** A new stream over everything collected so far. The caller closes it. */
    public synchronized InputStream openStream() throws IOException {
        if (file == null) {
            return new java.io.ByteArrayInputStream(memory == null ? new byte[0] : memory.toByteArray());
        }
        fileOut.flush();
        return Files.newInputStream(file);
    }

    /** A new stream over everything collected so far followed by {@code suffix}. The caller closes it. */
    public InputStream openStream(String suffix) throws IOException {
        byte[] tail = (suffix == null ? "" : suffix).getBytes(StandardCharsets.UTF_8);
        return new java.io.SequenceInputStream(openStream(), new java.io.ByteArrayInputStream(tail));
    }

    public static long sizeWith(StepOutputBuffer buffer, String suffix) {
        return buffer.sizeInBytes() + (suffix == null ? 0 : suffix.getBytes(StandardCharsets.UTF_8).length);
    }

    /** The whole output as text. Only for small outputs and tests, it loads everything. */
    @Override
    public synchronized String toString() {
        try (InputStream in = openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        memory = null;
        if (fileOut != null) {
            try {
                fileOut.close();
            } catch (IOException e) {
                log.debug("Unable to close step output file {}", file, e);
            }
            fileOut = null;
        }
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Unable to delete step output file {}", file, e);
            }
            file = null;
        }
    }
}
