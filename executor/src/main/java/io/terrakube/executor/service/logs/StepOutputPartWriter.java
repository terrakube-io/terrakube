package io.terrakube.executor.service.logs;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Copies the console output of a running step to the object store in numbered parts, so the part of the
 * output written so far survives an executor that dies before the step ends. The final object, written
 * when the step ends, is still the source of truth: this only writes what is already in the
 * {@link StepOutputBuffer}, reading from the offset after the last uploaded part, so nothing is held twice
 * and the memory bound of the buffer stays.
 *
 * A part is written when the bytes not yet uploaded reach the part size, or when the interval has passed
 * and there is something new. Part sizes grow in tiers so a very long step stays under {@code maxParts};
 * past the cap the writer stops. A part that cannot be uploaded is tried again, with a growing pause, from
 * the same offset. Nothing here ever fails the step.
 */
@Slf4j
public class StepOutputPartWriter {

    /** Where the parts go. {@code partNumber} starts at 1. A failure is retried by the writer. */
    public interface Sink {
        void put(int partNumber, PartSource data) throws IOException;
    }

    /** The bytes of one part. {@link #open()} is called again for each upload attempt. */
    public interface PartSource {
        InputStream open() throws IOException;

        long length();
    }

    static final long MAX_PART_BYTES = 64L * 1024 * 1024;
    private static final long POLL_MILLIS = 1000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final StepOutputBuffer buffer;
    private final Sink sink;
    private final long partBytes;
    private final long intervalMillis;
    private final int maxParts;
    private final LongSupplier clock;
    private final String description;
    private ScheduledExecutorService scheduler;
    private MeterRegistry meterRegistry;

    private long uploaded;
    private int parts;
    private long lastWrite;
    private int failures;
    private long retryNotBefore;
    private boolean capLogged;
    private boolean stopped;

    public StepOutputPartWriter(StepOutputBuffer buffer, Sink sink, long partBytes, Duration interval, int maxParts, String description) {
        this(buffer, sink, partBytes, interval, maxParts, description, System::currentTimeMillis);
    }

    StepOutputPartWriter(StepOutputBuffer buffer, Sink sink, long partBytes, Duration interval, int maxParts, String description, LongSupplier clock) {
        this.buffer = buffer;
        this.sink = sink;
        this.partBytes = Math.max(1, partBytes);
        this.intervalMillis = Math.max(0, interval.toMillis());
        this.maxParts = Math.max(1, maxParts);
        this.clock = clock;
        this.description = description;
        this.lastWrite = clock.getAsLong();
    }

    /** Counts parts written and failed in {@code terrakube.executor.output.parts}. Optional. */
    public StepOutputPartWriter withMetrics(MeterRegistry registry) {
        this.meterRegistry = registry;
        return this;
    }

    private void count(String result) {
        if (meterRegistry != null) {
            meterRegistry.counter("terrakube.executor.output.parts", "result", result).increment();
        }
    }

    public synchronized void start() {
        if (scheduler != null || stopped) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "step-output-parts");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safeTick, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Stops the writer, waits for an upload in progress, and returns the number of parts written. */
    public int stop() {
        ScheduledExecutorService toStop;
        synchronized (this) {
            stopped = true;
            toStop = scheduler;
            scheduler = null;
        }
        if (toStop != null) {
            toStop.shutdown();
            try {
                if (!toStop.awaitTermination(30, TimeUnit.SECONDS)) {
                    toStop.shutdownNow();
                    log.warn("Step output part upload for {} did not finish in time, leftover parts are removed with the job", description);
                }
            } catch (InterruptedException e) {
                toStop.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            return parts;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Unable to archive a part of the output of {}: {}", description, e.toString());
        }
    }

    /** One pass of the writer. Package visible so tests drive it without a thread. */
    synchronized void tick() {
        while (!stopped && archiveNextPart()) {
            // keeps going while there is another part to write
        }
    }

    /** Writes one part if there is something to write and it is time to. Returns whether to look for another. */
    private boolean archiveNextPart() {
        long now = clock.getAsLong();
        if (now < retryNotBefore || reachedCap()) {
            return false;
        }
        long pending = buffer.sizeInBytes() - uploaded;
        long size = partSize(parts + 1);
        boolean full = pending >= size;
        boolean due = now - lastWrite >= intervalMillis;
        if (pending <= 0 || (!full && !due)) {
            return false;
        }
        return upload(parts + 1, Math.min(pending, size), now);
    }

    private boolean reachedCap() {
        if (parts < maxParts) {
            return false;
        }
        if (!capLogged) {
            capLogged = true;
            log.info("Output of {} reached {} parts, not archiving more of it before the step ends", description, maxParts);
        }
        return true;
    }

    private boolean upload(int number, long length, long now) {
        try {
            sink.put(number, new PartSource() {
                @Override
                public InputStream open() throws IOException {
                    return buffer.openRange(uploaded, length);
                }

                @Override
                public long length() {
                    return length;
                }
            });
        } catch (IOException | RuntimeException e) {
            recordFailure(number, now, e);
            return false;
        }
        failures = 0;
        retryNotBefore = 0;
        uploaded += length;
        parts = number;
        count("written");
        lastWrite = now;
        return true;
    }

    private void recordFailure(int number, long now, Exception e) {
        failures++;
        count("failed");
        retryNotBefore = now + Math.min(MAX_BACKOFF_MILLIS, POLL_MILLIS << Math.min(failures, 5));
        if (failures == 1) {
            log.warn("Unable to archive part {} of the output of {}, will try again: {}", number, description, e.toString());
        } else {
            log.debug("Part {} of the output of {} failed again ({} in a row)", number, description, failures, e);
        }
    }

    /** 1 MiB (the configured size) for the first tier, doubling for each further tier of {@code maxParts / 8} parts. */
    long partSize(int number) {
        int tier = Math.max(1, maxParts / 8);
        int doublings = Math.min(20, (number - 1) / tier);
        long size = partBytes << doublings;
        return size <= 0 ? MAX_PART_BYTES : Math.min(size, Math.max(partBytes, MAX_PART_BYTES));
    }
}
