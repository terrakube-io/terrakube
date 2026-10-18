package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns one {@link JobLogBroadcaster} per active job and enforces a per-pod connection cap.
 */
@Component
@Slf4j
public class JobLogBroadcasterRegistry {

    private final RedisStreamReader reader;
    private final JobStatusCache jobStatusCache;
    private final LogsProperties properties;

    private final ConcurrentHashMap<String, JobLogBroadcaster> broadcasters = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger(0);
    private final Semaphore replayReadPermits;

    public JobLogBroadcasterRegistry(RedisStreamReader reader, JobStatusCache jobStatusCache,
                                     LogsProperties properties) {
        this.reader = reader;
        this.jobStatusCache = jobStatusCache;
        this.properties = properties;
        this.replayReadPermits = new Semaphore(Math.max(1, properties.getSseMaxConcurrentReplayReads()));
    }

    public SseEmitter subscribe(String jobId, String stepId, RecordId resumeFrom) {
        int limit = properties.getSseMaxConnections();
        if (connections.incrementAndGet() > limit) {
            connections.decrementAndGet();
            throw new SseCapacityExceededException(limit);
        }

        SseEmitter emitter = new SseEmitter(properties.getSseMaxStreamDuration().toMillis());

        JobLogBroadcaster.Subscription subscription;
        do {
            JobLogBroadcaster broadcaster = broadcasters.compute(jobId, (id, existing) -> {
                if (existing != null && !existing.isFinished()) {
                    return existing;
                }
                return new JobLogBroadcaster(id, reader, jobStatusCache, properties,
                        () -> broadcasters.computeIfPresent(id, (k, v) -> v.isFinished() ? null : v),
                        replayReadPermits);
            });
            // Null when the broadcaster finished between compute and subscribe: retry with a fresh one.
            subscription = broadcaster.subscribe(stepId, emitter, resumeFrom);
            if (subscription != null) {
                broadcaster.start();
            }
        } while (subscription == null);

        JobLogBroadcaster.Subscription attached = subscription;
        Runnable release = () -> {
            attached.close();
            connections.decrementAndGet();
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(t -> release.run());

        return emitter;
    }

    public int activeConnections() {
        return connections.get();
    }

    public int activeJobs() {
        return broadcasters.size();
    }
}
