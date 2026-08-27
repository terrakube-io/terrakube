package io.terrakube.api.plugin.logs;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.JobStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a step's archived log. A terminal step's log is immutable, so small ones are cached
 * (Caffeine) in front of {@link StorageTypeService}; large ones and running steps are handled by the
 * caller. Nothing here opens a database transaction or holds a connection across the storage read.
 */
@Service
@Slf4j
public class StepLogService {

    private final StorageTypeService storage;
    private final StepRepository stepRepository;
    private final LogsProperties properties;
    private final Cache<String, byte[]> cache;

    public StepLogService(StorageTypeService storage, StepRepository stepRepository, LogsProperties properties) {
        this.storage = storage;
        this.stepRepository = stepRepository;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumWeight(properties.getCacheMaxWeightBytes())
                .weigher((String k, byte[] v) -> v.length)
                .expireAfterWrite(properties.getCacheTtl())
                .build();
    }

    @Getter
    public static final class StepLog {
        private final byte[] body;          // null when the object is large (caller must stream)
        private final long contentLength;
        private final boolean exists;
        private final boolean terminal;

        private StepLog(byte[] body, long contentLength, boolean exists, boolean terminal) {
            this.body = body;
            this.contentLength = contentLength;
            this.exists = exists;
            this.terminal = terminal;
        }

        public static StepLog missing() {
            return new StepLog(null, -1L, false, false);
        }

        public static StepLog cached(byte[] body) {
            return new StepLog(body, body.length, true, true);
        }

        public static StepLog streamable(long contentLength) {
            return new StepLog(null, contentLength, true, true);
        }
    }

    /**
     * The step's status, or empty when the id is not a known step (or not a UUID at all - the
     * {@code /tfoutput} endpoint has always allowed path-based access with arbitrary ids).
     */
    private Optional<JobStatus> stepStatus(String stepId) {
        try {
            return stepRepository.findById(UUID.fromString(stepId)).map(step -> step.getStatus());
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    public StepLog resolve(String organizationId, String jobId, String stepId) {
        Optional<JobStatus> status = stepStatus(stepId);

        // A step that is running or about to run streams to Redis - the caller serves that live
        // path instead of a half-written or absent archived object.
        if (status.filter(s -> s == JobStatus.running || s == JobStatus.pending).isPresent()) {
            return StepLog.missing();
        }

        boolean terminal = status
                .map(s -> s == JobStatus.completed || s == JobStatus.failed || s == JobStatus.cancelled)
                .orElse(false);

        String key = cacheKey(organizationId, jobId, stepId);
        byte[] hit = cache.getIfPresent(key);
        if (hit != null) {
            return StepLog.cached(hit);
        }
        try (StepOutputStream out = storage.getStepOutputStream(organizationId, jobId, stepId, null)) {
            if (!out.isExists()) {
                return StepLog.missing();
            }
            long len = out.getContentLength();
            if (len >= 0 && len <= properties.getCacheableMaxObjectBytes()) {
                byte[] body = StreamUtils.copyToByteArray(out.getContent());
                if (terminal) {
                    cache.put(key, body);
                }
                return StepLog.cached(body);
            }
            return StepLog.streamable(len);
        } catch (IOException e) {
            log.error("Failed reading step log {}: {}", key, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public StepOutputStream openStream(String organizationId, String jobId, String stepId, ByteRange range) {
        return storage.getStepOutputStream(organizationId, jobId, stepId, range);
    }

    private String cacheKey(String org, String job, String step) {
        return org + "/" + job + "/" + step;
    }
}
