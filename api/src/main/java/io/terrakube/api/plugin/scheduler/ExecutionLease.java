package io.terrakube.api.plugin.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One worker's hold on a job's Redis execution lock. The value stored under the key is a token
 * unique to this hold, so every later operation is checked against it in Redis: a worker whose
 * lock expired can neither extend, release nor dispatch under the lock a successor now holds.
 *
 * <p>Every Redis failure is read as "ownership not confirmed" (fail closed).
 */
@Slf4j
public class ExecutionLease {
    static final String LOCK_PREFIX = "job-execution-lock:";
    static final String CLAIM_PREFIX = "job-dispatch-claim:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    // Deletes a claim only while it is still this token's.
    private static final DefaultRedisScript<Long> UNCLAIM_SCRIPT = RELEASE_SCRIPT;

    // The TTLs are written into the script as number literals rather than passed as arguments:
    // arguments go through the template's value serializer (JDK serialization here), which turns a
    // number into bytes Redis cannot read as an integer. Only the token, which was stored through
    // the same serializer, is passed in.
    private static final Map<String, DefaultRedisScript<Long>> TTL_SCRIPTS = new ConcurrentHashMap<>();

    private static DefaultRedisScript<Long> renewScript(Duration ttl) {
        return TTL_SCRIPTS.computeIfAbsent("renew:" + ttl.toMillis(), key -> new DefaultRedisScript<>(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], "
                        + ttl.toMillis() + ") else return 0 end", Long.class));
    }

    // KEYS[1] = execution lock, KEYS[2] = dispatch claim. The claim is only written while this
    // token still owns the lock, and only if nobody has claimed this step before, in one step.
    private static DefaultRedisScript<Long> claimScript(Duration ttl) {
        return TTL_SCRIPTS.computeIfAbsent("claim:" + ttl.toMillis(), key -> new DefaultRedisScript<>(
                "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end "
                        + "if redis.call('set', KEYS[2], ARGV[1], 'NX', 'PX', " + ttl.toMillis()
                        + ") then return 1 else return 0 end", Long.class));
    }

    private final RedisTemplate<String, Object> redisTemplate;
    private final int jobId;
    private final String token;
    private final Duration lockTtl;
    private final Duration claimTtl;
    private final AtomicBoolean lost = new AtomicBoolean(false);

    private ExecutionLease(RedisTemplate<String, Object> redisTemplate, int jobId, Duration lockTtl, Duration claimTtl) {
        this.redisTemplate = redisTemplate;
        this.jobId = jobId;
        this.token = UUID.randomUUID().toString();
        this.lockTtl = lockTtl;
        this.claimTtl = claimTtl;
    }

    /** Returns the lease when this worker got the lock; empty when it is held elsewhere or Redis is unreachable. */
    public static Optional<ExecutionLease> acquire(RedisTemplate<String, Object> redisTemplate, int jobId,
            Duration lockTtl, Duration claimTtl) {
        ExecutionLease lease = new ExecutionLease(redisTemplate, jobId, lockTtl, claimTtl);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + jobId, lease.token, lockTtl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(lease) : Optional.empty();
        } catch (DataAccessException e) {
            log.warn("Could not reach Redis to acquire execution lock for Job {}, will retry: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Extends the lock if this worker still owns it. Marks the lease lost when it does not. */
    public boolean renew() {
        if (lost.get()) {
            return false;
        }
        boolean owned = runScript(renewScript(lockTtl), List.of(LOCK_PREFIX + jobId), token);
        if (!owned) {
            lost.set(true);
            log.error("Job {} lost its execution lock before this worker finished; it will not dispatch", jobId);
        }
        return owned;
    }

    public boolean isLost() {
        return lost.get();
    }

    /**
     * Claims the right to submit this step to an executor. True only if this worker still owns the
     * lock and no other attempt has claimed the step. The claim outlives the lock on purpose, so a
     * second worker cannot submit the same step after this one's lock expires.
     */
    public boolean claimDispatch(String stepId) {
        if (lost.get()) {
            return false;
        }
        boolean claimed = runScript(claimScript(claimTtl), List.of(LOCK_PREFIX + jobId, claimKey(stepId)), token);
        if (!claimed) {
            log.warn("Job {} Step {} was not claimed for dispatch: the lock is lost or the step was already claimed", jobId, stepId);
        }
        return claimed;
    }

    /** For a submission that definitely did not reach an executor, so the step may be dispatched again. */
    public void releaseDispatchClaim(String stepId) {
        runScript(UNCLAIM_SCRIPT, List.of(claimKey(stepId)), token);
    }

    public void release() {
        runScript(RELEASE_SCRIPT, List.of(LOCK_PREFIX + jobId), token);
    }

    private static String claimKey(int jobId, String stepId) {
        return CLAIM_PREFIX + jobId + ":" + stepId;
    }

    private String claimKey(String stepId) {
        return claimKey(jobId, stepId);
    }

    private boolean runScript(DefaultRedisScript<Long> script, List<String> keys, Object... args) {
        try {
            Long result = redisTemplate.execute(script, keys, args);
            return result != null && result > 0;
        } catch (DataAccessException e) {
            // The lock's TTL self-heals a failed release; for renew and claim this is "not owned".
            log.warn("Could not reach Redis for the execution lock of Job {}: {}", jobId, e.getMessage());
            return false;
        }
    }
}
