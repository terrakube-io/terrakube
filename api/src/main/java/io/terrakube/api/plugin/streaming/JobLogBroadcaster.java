package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One Redis {@code XREAD} loop per job, fanned out to every viewer filtered by {@code stepId}.
 *
 * <p>Each viewer has its own cursor: it attaches to the live fan-out first, replays history from its resume
 * id in pages, then drains what was queued meanwhile, skipping ids already sent. It is written to from its
 * own byte-bounded queue, so a slow viewer is disconnected (and resumes by cursor) rather than delaying others.
 */
@Slf4j
public class JobLogBroadcaster {

    private static final int PER_RECORD_OVERHEAD_BYTES = 64;
    private static final long QUEUE_POLL_MS = 250;

    private final String jobId;
    private final RedisStreamReader reader;
    private final JobStatusCache jobStatusCache;
    private final LogsProperties properties;
    private final Runnable onEmpty;
    private final Semaphore replayReadPermits;

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean onEmptyFired = new AtomicBoolean(false);
    private final AtomicLong slowViewersDisconnected = new AtomicLong();

    private final AtomicReference<RecordId> lastId = new AtomicReference<>(RecordId.of("0-0"));

    public JobLogBroadcaster(String jobId, RedisStreamReader reader, JobStatusCache jobStatusCache,
                             LogsProperties properties, Runnable onEmpty) {
        this(jobId, reader, jobStatusCache, properties, onEmpty,
                new Semaphore(Math.max(1, properties.getSseMaxConcurrentReplayReads())));
    }

    /** @param replayReadPermits shared by all broadcasters of the pod. */
    public JobLogBroadcaster(String jobId, RedisStreamReader reader, JobStatusCache jobStatusCache,
                             LogsProperties properties, Runnable onEmpty, Semaphore replayReadPermits) {
        this.replayReadPermits = replayReadPermits;
        this.jobId = jobId;
        this.reader = reader;
        this.jobStatusCache = jobStatusCache;
        this.properties = properties;
        this.onEmpty = onEmpty;
    }

    public final class Subscription implements Runnable {
        private final String stepId;
        private final SseEmitter emitter;
        private final RecordId resumeFrom;

        private final LinkedBlockingQueue<MapRecord> queue = new LinkedBlockingQueue<>();
        private final AtomicLong queuedBytes = new AtomicLong();
        private final Object stateLock = new Object();
        private boolean live;
        private boolean overflowed;
        private volatile boolean closed;
        private volatile boolean slow;
        private volatile String endReason;
        private RecordId cursor;
        private long lastSendNanos = System.nanoTime();

        private Subscription(String stepId, SseEmitter emitter, RecordId resumeFrom) {
            this.stepId = stepId;
            this.emitter = emitter;
            this.resumeFrom = resumeFrom;
            this.cursor = resumeFrom;
        }

        public void close() {
            closed = true;
            removeSubscriber(this);
        }

        /** Whether replay is done and this viewer is fed from live fan-out. */
        boolean isLive() {
            synchronized (stateLock) {
                return live;
            }
        }

        /** Called from the fan-out loop; never blocks. */
        private void offer(MapRecord record, String output) {
            if (closed) {
                return;
            }
            long size = sizeOf(output);
            synchronized (stateLock) {
                if (queuedBytes.get() + size > properties.getSseSubscriberMaxQueuedBytes()) {
                    if (live) {
                        slowViewersDisconnected.incrementAndGet();
                        log.warn("Disconnecting slow log viewer of job {} step {} (more than {} bytes behind); it resumes from {}",
                                jobId, stepId, properties.getSseSubscriberMaxQueuedBytes(), cursor);
                        closed = true;
                        slow = true;
                        queue.clear();
                        queuedBytes.set(0);
                    } else {
                        // Still replaying: the dropped record is re-read by the next replay round.
                        overflowed = true;
                    }
                    return;
                }
                queue.add(record);
                queuedBytes.addAndGet(size);
            }
        }

        @Override
        public void run() {
            try {
                replayHistory();
                drainLive();
                if (slow) {
                    // Best effort, once the client reads again.
                    emitter.send(SseEmitter.event().name("end").data("{\"reason\":\"overflow\"}"));
                }
            } catch (IOException | IllegalStateException e) {
                // Client went away.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Log viewer of job {} step {} stopped: {}", jobId, stepId, e.getMessage());
            } finally {
                closed = true;
                removeSubscriber(this);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already completed
                }
            }
        }

        private void replayHistory() throws IOException {
            if (StreamIds.isZero(resumeFrom) && properties.getSseFreshViewerTailRecords() > 0) {
                sendRecentTailToFreshViewer(properties.getSseFreshViewerTailRecords());
            } else {
                sendGapIfHistoryTrimmed();
            }
            int pageSize = Math.max(1, properties.getSseReplayPageSize());
            while (!closed) {
                synchronized (stateLock) {
                    overflowed = false;
                    // Anything queued so far is re-read by the replay below.
                    queue.clear();
                    queuedBytes.set(0);
                }
                List<MapRecord> page;
                do {
                    page = readReplayPage(pageSize);
                    for (MapRecord record : page) {
                        send(record);
                    }
                } while (page.size() >= pageSize && !closed);

                synchronized (stateLock) {
                    if (!overflowed) {
                        live = true;
                        return;
                    }
                }
            }
        }

        // A first-time viewer starts from the newest records; the paged replay catches up the rest.
        private void sendRecentTailToFreshViewer(int tailRecords) throws IOException {
            List<MapRecord> tail;
            replayReadPermits.acquireUninterruptibly();
            try {
                tail = reader.readTail(jobId, tailRecords);
            } finally {
                replayReadPermits.release();
            }
            if (tail == null || tail.isEmpty()) {
                return;
            }
            RecordId oldestKept = tail.get(0).getId();
            RecordId oldestRetained = tail.size() >= tailRecords ? reader.firstEntryId(jobId) : null;
            if (oldestRetained != null && StreamIds.compare(oldestRetained, oldestKept) < 0) {
                emitter.send(SseEmitter.event().name("gap").data(
                        "{\"reason\":\"history-limited\",\"afterId\":\"" + resumeFrom.getValue()
                                + "\",\"firstAvailableId\":\"" + oldestKept.getValue() + "\",\"recovery\":\"step-log\"}"));
                lastSendNanos = System.nanoTime();
            }
            for (MapRecord record : tail) {
                send(record);
            }
        }

        // The permit covers only the Redis read, not the send.
        private List<MapRecord> readReplayPage(int pageSize) {
            replayReadPermits.acquireUninterruptibly();
            try {
                return reader.readAfterPage(jobId, cursor, pageSize);
            } finally {
                replayReadPermits.release();
            }
        }

        private void sendGapIfHistoryTrimmed() throws IOException {
            if (StreamIds.isZero(resumeFrom)) {
                return;
            }
            RecordId oldest = reader.firstEntryId(jobId);
            if (oldest != null && StreamIds.compare(resumeFrom, oldest) < 0) {
                emitter.send(SseEmitter.event().name("gap").data(
                        "{\"reason\":\"trimmed\",\"afterId\":\"" + resumeFrom.getValue()
                                + "\",\"firstAvailableId\":\"" + oldest.getValue() + "\",\"recovery\":\"step-log\"}"));
                lastSendNanos = System.nanoTime();
            }
        }

        private void drainLive() throws IOException, InterruptedException {
            long heartbeatNanos = properties.getSseHeartbeatInterval().toNanos();
            while (!closed) {
                MapRecord record = queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS);
                if (record == null) {
                    String reason = endReason;
                    if (reason != null) {
                        emitter.send(SseEmitter.event().name("end").data("{\"reason\":\"" + reason + "\"}"));
                        return;
                    }
                    if (System.nanoTime() - lastSendNanos >= heartbeatNanos) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastSendNanos = System.nanoTime();
                    }
                    continue;
                }
                queuedBytes.addAndGet(-sizeOf(outputOf(record)));
                send(record);
            }
        }

        /** Sends a record unless this viewer was already sent it or a later one. */
        private void send(MapRecord record) throws IOException {
            if (StreamIds.compare(record.getId(), cursor) <= 0) {
                return;
            }
            cursor = record.getId();
            if (!stepId.equals(stepOf(record))) {
                return;
            }
            emitter.send(SseEmitter.event().id(record.getId().getValue()).data(outputOf(record)));
            lastSendNanos = System.nanoTime();
        }
    }

    public Subscription subscribe(String stepId, SseEmitter emitter, RecordId resumeFrom) {
        Subscription subscription = new Subscription(stepId, emitter, resumeFrom);
        synchronized (lifecycleLock) {
            // Attach before replaying so nothing falls between replay and live.
            subscriptions.add(subscription);
        }
        Thread.ofVirtual().name("joblog-viewer-" + jobId).start(subscription);
        return subscription;
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    public long slowViewersDisconnected() {
        return slowViewersDisconnected.get();
    }

    public boolean isFinished() {
        return finished.get();
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            // Fan out only new records; each viewer replays earlier ones itself.
            RecordId end = reader.lastEntryId(jobId);
            if (end != null) {
                lastId.set(end);
            }
            Thread.ofVirtual().name("joblog-" + jobId).start(this::runLoop);
        }
    }

    private void runLoop() {
        Instant deadline = Instant.now().plus(properties.getSseMaxStreamDuration());
        try {
            while (!finished.get()) {
                if (subscriptions.isEmpty()) {
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    endAll("rotate");
                    return;
                }

                List<MapRecord> records = reader.readAfter(jobId, lastId.get(), properties.getSseJobIdleTimeout());

                if (records.isEmpty()) {
                    if (jobStatusCache.isTerminal(jobId)) {
                        endAll("complete");
                        return;
                    }
                    continue;
                }
                dispatch(records);
            }
        } catch (Exception e) {
            log.error("Job log broadcaster for job {} stopped: {}", jobId, e.getMessage());
            endAll("rotate");
        } finally {
            fireOnEmpty();
        }
    }

    private void dispatch(List<MapRecord> records) {
        for (MapRecord record : records) {
            lastId.set(record.getId());
            String stepId = stepOf(record);
            String output = outputOf(record);
            for (Subscription subscription : subscriptions) {
                if (subscription.stepId.equals(stepId)) {
                    subscription.offer(record, output);
                }
            }
        }
    }

    private void removeSubscriber(Subscription subscription) {
        synchronized (lifecycleLock) {
            subscriptions.remove(subscription);
            if (subscriptions.isEmpty() && started.get()) {
                finished.set(true);
                fireOnEmpty();
            }
        }
    }

    /** Tells every viewer why the stream ends, after its backlog. */
    private void endAll(String reason) {
        for (Subscription subscription : subscriptions) {
            subscription.endReason = reason;
        }
        fireOnEmpty();
    }

    /** Runs {@link #onEmpty} exactly once, whichever exit path reaches it first. */
    private void fireOnEmpty() {
        if (onEmptyFired.compareAndSet(false, true)) {
            finished.set(true);
            onEmpty.run();
        }
    }

    private static String stepOf(MapRecord record) {
        return StringRecord.of(record).getValue().get("stepId");
    }

    private static String outputOf(MapRecord record) {
        return StringRecord.of(record).getValue().get("output");
    }

    private static long sizeOf(String output) {
        return PER_RECORD_OVERHEAD_BYTES + (output == null ? 0 : output.length());
    }
}
