package io.terrakube.api.plugin.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A short per-job Redis lock around a {@code /context/v1} read-apply-write, so writers on different
 * API pods are serialized and each one merges into the latest stored document. No database
 * transaction or row lock is held while the object store is read and written. When Redis cannot be
 * reached the write fails closed with a {@link ContextUnavailableException}.
 */
@Slf4j
@Component
public class ContextWriteLock {
    static final String KEY_PREFIX = "job-context-write-lock:";

    // ponytail: the TTL must exceed the worst-case storage read+write. If it expires mid-write, a
    // second writer can interleave and one update is lost. Upgrade path: conditional writes in
    // storage (ETag / generation).
    private static final Duration TTL = Duration.ofSeconds(60);

    private static final long RETRY_INTERVAL_MILLIS = 50;

    // Deletes the key only while it still holds this writer's token, so a writer whose lock expired
    // cannot release the next writer's lock.
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final RedisTemplate<String, Object> redisTemplate;

    private final long acquireTimeoutMillis;

    @Autowired
    public ContextWriteLock(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, 5_000);
    }

    ContextWriteLock(RedisTemplate<String, Object> redisTemplate, long acquireTimeoutMillis) {
        this.redisTemplate = redisTemplate;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    /**
     * Waits up to the acquire timeout (5s by default) for the job's lock.
     *
     * @return the token to {@link #release} it with.
     * @throws ContextUnavailableException when it is still busy, Redis cannot be reached, or the wait is interrupted.
     */
    String acquire(int jobId) {
        String key = KEY_PREFIX + jobId;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
        try {
            while (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, TTL))) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new ContextUnavailableException("context write lock for job " + jobId + " is busy", null);
                }
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            }
        } catch (DataAccessException e) {
            throw new ContextUnavailableException("cannot reach Redis for the context write lock of job " + jobId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContextUnavailableException("interrupted waiting for the context write lock of job " + jobId, e);
        }
        return token;
    }

    void release(int jobId, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + jobId), token);
        } catch (DataAccessException e) {
            // The TTL self-heals this; nothing else to do here.
            log.warn("Could not reach Redis to release the context write lock for job {}: {}", jobId, e.getMessage());
        }
    }
}
