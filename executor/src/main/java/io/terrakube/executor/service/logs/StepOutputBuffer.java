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
    private ExposedBytes memory = new ExposedBytes();
    private Path file;
    private OutputStream fileOut;
    private long bytes;
    private boolean closed;
    private StepOutputPartWriter archiver;

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

    /**
     * A new stream over {@code length} bytes starting at {@code offset} of what has been collected so far
     * (fewer if the output is shorter). Only the requested range is copied or read, so archiving a part
     * does not hold the whole output twice. The caller closes it.
     */
    public synchronized InputStream openRange(long offset, long length) throws IOException {
        long available = Math.clamp(bytes - offset, 0L, Math.max(0L, length));
        if (available == 0 || offset < 0) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        if (file == null) {
            return new java.io.ByteArrayInputStream(memory == null ? new byte[0] : memory.copy((int) offset, (int) available));
        }
        fileOut.flush();
        InputStream in = Files.newInputStream(file);
        try {
            in.skipNBytes(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return new BoundedInputStream(in, available);
    }

    /** Starts copying the output to the object store in parts while the step runs. See {@link StepOutputPartWriter}. */
    public synchronized void attachArchiver(StepOutputPartWriter writer) {
        this.archiver = writer;
    }

    /**
     * Stops the part writer, if any, and returns the number of parts it wrote. Called before the final
     * upload, which holds the whole output; the parts are only for a step that never gets that far.
     */
    public int finishArchiving() {
        StepOutputPartWriter writer;
        synchronized (this) {
            writer = archiver;
            archiver = null;
        }
        return writer == null ? 0 : writer.stop();
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
    public void close() {
        finishArchiving();
        closeFiles();
    }

    private synchronized void closeFiles() {
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

    private static final class ExposedBytes extends ByteArrayOutputStream {
        byte[] copy(int from, int length) {
            return java.util.Arrays.copyOfRange(buf, from, from + length);
        }
    }

    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            if (remaining <= 0 || n <= 0) {
                return 0;
            }
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= Math.max(0, skipped);
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.clamp(remaining, 0, super.available());
        }
    }
}
