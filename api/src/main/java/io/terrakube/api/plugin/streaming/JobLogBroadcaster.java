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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One Redis {@code XREAD} loop for a single job's log stream, fanning each record out to every
 * attached {@link SseEmitter} filtered by {@code stepId}. Cost scales with the number of active jobs,
 * not the number of viewers: 50 people watching one run share this one loop.
 */
@Slf4j
public class JobLogBroadcaster {

    private static final int HEARTBEAT_EVERY_N_EMPTY_READS = 8;

    private final String jobId;
    private final RedisStreamReader reader;
    private final JobStatusCache jobStatusCache;
    private final LogsProperties properties;
    private final Runnable onEmpty;

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean onEmptyFired = new AtomicBoolean(false);

    private volatile RecordId lastId = RecordId.of("0-0");

    public JobLogBroadcaster(String jobId, RedisStreamReader reader, JobStatusCache jobStatusCache,
                             LogsProperties properties, Runnable onEmpty) {
        this.jobId = jobId;
        this.reader = reader;
        this.jobStatusCache = jobStatusCache;
        this.properties = properties;
        this.onEmpty = onEmpty;
    }

    public final class Subscription {
        private final String stepId;
        private final SseEmitter emitter;

        private Subscription(String stepId, SseEmitter emitter) {
            this.stepId = stepId;
            this.emitter = emitter;
        }

        public void close() {
            removeSubscriber(this);
        }
    }

    public Subscription subscribe(String stepId, SseEmitter emitter, RecordId resumeFrom) {
        Subscription subscription = new Subscription(stepId, emitter);
        synchronized (lifecycleLock) {
            if (!started.get() && isOlder(resumeFrom, lastId)) {
                lastId = resumeFrom;
            }
            subscriptions.add(subscription);
        }
        return subscription;
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    public boolean isFinished() {
        return finished.get();
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            Thread.ofVirtual().name("joblog-" + jobId).start(this::runLoop);
        }
    }

    private void runLoop() {
        Instant deadline = Instant.now().plus(properties.getSseMaxStreamDuration());
        int emptyReads = 0;
        try {
            while (!finished.get()) {
                if (subscriptions.isEmpty() || Instant.now().isAfter(deadline)) {
                    completeAll();
                    return;
                }

                List<MapRecord> records = reader.readAfter(jobId, lastId, properties.getSseJobIdleTimeout());

                if (records.isEmpty()) {
                    emptyReads++;
                    if (jobStatusCache.isTerminal(jobId)) {
                        completeAll();
                        return;
                    }
                    if (emptyReads % HEARTBEAT_EVERY_N_EMPTY_READS == 0) {
                        heartbeatAll();
                    }
                    continue;
                }

                emptyReads = 0;
                dispatch(records);
            }
        } catch (Exception e) {
            log.error("Job log broadcaster for job {} stopped: {}", jobId, e.getMessage());
            completeAll();
        } finally {
            fireOnEmpty();
        }
    }

    private void dispatch(List<MapRecord> records) {
        for (MapRecord record : records) {
            lastId = record.getId();
            StringRecord stringRecord = StringRecord.of(record);
            String stepId = stringRecord.getValue().get("stepId");
            String output = stringRecord.getValue().get("output");
            for (Subscription subscription : subscriptions) {
                if (subscription.stepId.equals(stepId)) {
                    trySend(subscription, output);
                }
            }
        }
    }

    private void trySend(Subscription subscription, String output) {
        try {
            subscription.emitter.send(SseEmitter.event().id(lastId.getValue()).data(output));
        } catch (IOException | IllegalStateException e) {
            removeSubscriber(subscription);
        }
    }

    private void heartbeatAll() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                removeSubscriber(subscription);
            }
        }
        if (subscriptions.isEmpty()) {
            fireOnEmpty();
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

    private void completeAll() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter.complete();
            } catch (Exception ignored) {
                // emitter already closed by the client
            }
        }
        subscriptions.clear();
        fireOnEmpty();
    }

    /** Runs {@link #onEmpty} exactly once, whichever exit path reaches it first. */
    private void fireOnEmpty() {
        if (onEmptyFired.compareAndSet(false, true)) {
            finished.set(true);
            onEmpty.run();
        }
    }

    private static boolean isOlder(RecordId a, RecordId b) {
        if (a.getTimestamp() != b.getTimestamp()) {
            return a.getTimestamp() < b.getTimestamp();
        }
        return a.getSequence() < b.getSequence();
    }
}
