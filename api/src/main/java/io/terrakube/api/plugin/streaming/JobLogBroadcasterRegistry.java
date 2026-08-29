package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns one {@link JobLogBroadcaster} per active job. A step-log SSE subscriber gets a one-shot
 * catch-up read, then joins its job's shared broadcaster loop. Enforces a per-pod connection cap.
 */
@Component
@Slf4j
public class JobLogBroadcasterRegistry {

    private final RedisStreamReader reader;
    private final JobStatusCache jobStatusCache;
    private final LogsProperties properties;

    private final ConcurrentHashMap<String, JobLogBroadcaster> broadcasters = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger(0);

    public JobLogBroadcasterRegistry(RedisStreamReader reader, JobStatusCache jobStatusCache,
                                     LogsProperties properties) {
        this.reader = reader;
        this.jobStatusCache = jobStatusCache;
        this.properties = properties;
    }

    public SseEmitter subscribe(String jobId, String stepId, RecordId resumeFrom) {
        int limit = properties.getSseMaxConnections();
        if (connections.incrementAndGet() > limit) {
            connections.decrementAndGet();
            throw new SseCapacityExceededException(limit);
        }

        SseEmitter emitter = new SseEmitter(properties.getSseMaxStreamDuration().toMillis());

        RecordId effectiveResumeFrom = backfill(jobId, stepId, resumeFrom, emitter);

        JobLogBroadcaster broadcaster = broadcasters.compute(jobId, (id, existing) -> {
            if (existing != null && !existing.isFinished()) {
                return existing;
            }
            return new JobLogBroadcaster(id, reader, jobStatusCache, properties,
                    () -> broadcasters.remove(id));
        });

        JobLogBroadcaster.Subscription subscription = broadcaster.subscribe(stepId, emitter, effectiveResumeFrom);
        broadcaster.start();

        Runnable release = () -> {
            subscription.close();
            connections.decrementAndGet();
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(t -> release.run());

        return emitter;
    }

    private RecordId backfill(String jobId, String stepId, RecordId resumeFrom, SseEmitter emitter) {
        RecordId lastSent = resumeFrom;
        try {
            List<MapRecord> records = reader.readAfterOnce(jobId, resumeFrom);
            for (MapRecord record : records) {
                StringRecord stringRecord = StringRecord.of(record);
                if (!stepId.equals(stringRecord.getValue().get("stepId"))) {
                    lastSent = record.getId();
                    continue;
                }
                emitter.send(SseEmitter.event()
                        .id(record.getId().getValue())
                        .data(stringRecord.getValue().get("output")));
                lastSent = record.getId();
            }
        } catch (Exception e) {
            log.warn("Catch-up read failed for job {} step {}: {}", jobId, stepId, e.getMessage());
        }
        return lastSent;
    }

    public int activeConnections() {
        return connections.get();
    }

    public int activeJobs() {
        return broadcasters.size();
    }
}
