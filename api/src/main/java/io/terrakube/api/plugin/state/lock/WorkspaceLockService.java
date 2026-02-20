package io.terrakube.api.plugin.state.lock;

import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Distributed workspace lock service using Redis SET NX for atomic lock acquisition
 * and PostgreSQL for lock metadata persistence (audit trail).
 *
 * Lock flow:
 * 1. Redis SET NX EX atomically acquires the lock (prevents race conditions across replicas)
 * 2. DB stores lock metadata for API responses and audit
 * 3. Redis TTL provides automatic lock expiration (no cleanup job needed)
 * 4. Unlock uses Lua script for atomic check-and-delete (only owner can unlock)
 */
@Slf4j
@Service
public class WorkspaceLockService {

    private static final String LOCK_KEY_PREFIX = "terrakube:lock:ws:";
    private static final String SEPARATOR = "::";

    /**
     * Lua script for atomic unlock: checks that the lock value matches before deleting.
     * Returns 1 if deleted, 0 if lock doesn't exist or doesn't match.
     */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final RedisTemplate<String, Object> redisTemplate;
    private final WorkspaceRepository workspaceRepository;
    private final long lockTimeoutMinutes;

    public WorkspaceLockService(
            RedisTemplate<String, Object> redisTemplate,
            WorkspaceRepository workspaceRepository,
            @Value("${terrakube.state.lock.timeout-minutes:10}") long lockTimeoutMinutes) {
        this.redisTemplate = redisTemplate;
        this.workspaceRepository = workspaceRepository;
        this.lockTimeoutMinutes = lockTimeoutMinutes;
    }

    /**
     * Attempt to acquire a lock on a workspace.
     *
     * Uses Redis SET NX EX for atomic, distributed lock acquisition.
     * If successful, persists lock metadata to the database.
     *
     * @param workspaceId the workspace UUID
     * @param userId      the user or service acquiring the lock
     * @param reason      optional reason for locking
     * @return LockResult indicating success or conflict with existing lock info
     */
    @Transactional
    public LockResult acquireLock(String workspaceId, String userId, String reason) {
        String lockId = UUID.randomUUID().toString();
        String lockValue = buildLockValue(lockId, userId);
        String redisKey = buildRedisKey(workspaceId);
        Duration timeout = Duration.ofMinutes(lockTimeoutMinutes);

        log.info("Attempting to acquire lock for workspace {} by user {}", workspaceId, userId);

        // Atomic lock acquisition via Redis SET NX EX
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, lockValue, timeout);

        if (Boolean.TRUE.equals(acquired)) {
            // Lock acquired in Redis - persist metadata to DB for API responses
            persistLockToDb(workspaceId, lockId, userId, reason);
            log.info("Lock acquired for workspace {} by user {}, lockId={}", workspaceId, userId, lockId);
            return LockResult.acquired(lockId);
        }

        // Lock not acquired - return conflict with existing lock info
        LockInfo existingLock = getLockInfo(workspaceId);
        if (existingLock != null) {
            log.warn("Lock conflict for workspace {}: already locked by {} since {}",
                    workspaceId, existingLock.getLockedBy(), existingLock.getLockedAt());
            return LockResult.conflict(existingLock);
        }

        // Edge case: Redis key expired between our SET NX and GET, retry once
        acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, lockValue, timeout);
        if (Boolean.TRUE.equals(acquired)) {
            persistLockToDb(workspaceId, lockId, userId, reason);
            log.info("Lock acquired on retry for workspace {} by user {}, lockId={}", workspaceId, userId, lockId);
            return LockResult.acquired(lockId);
        }

        // Still can't get it
        return LockResult.conflict(getLockInfoFromDb(workspaceId));
    }

    /**
     * Release a lock on a workspace. Only the lock owner (matching lockId) can release.
     *
     * Uses a Lua script for atomic check-and-delete in Redis.
     *
     * @param workspaceId the workspace UUID
     * @param lockId      the lock ID that was returned when the lock was acquired (nullable for backward compat)
     * @return LockResult indicating success, not-locked, or not-owner
     */
    @Transactional
    public LockResult releaseLock(String workspaceId, String lockId) {
        String redisKey = buildRedisKey(workspaceId);

        log.info("Attempting to release lock for workspace {}, lockId={}", workspaceId, lockId);

        // If no lockId provided (backward compatibility), just clear everything
        if (lockId == null || lockId.isEmpty()) {
            return forceReleaseLock(workspaceId);
        }

        // Get current lock value from Redis to extract the full value for comparison
        Object currentValue = redisTemplate.opsForValue().get(redisKey);
        if (currentValue == null) {
            // Redis key already expired or doesn't exist - clean up DB
            clearLockFromDb(workspaceId);
            log.info("Lock already expired for workspace {}, cleaned up DB", workspaceId);
            return LockResult.released();
        }

        // Extract lockId from stored value and compare
        String storedLockId = extractLockId(currentValue.toString());
        if (!lockId.equals(storedLockId)) {
            LockInfo existingLock = getLockInfo(workspaceId);
            log.warn("Lock release denied for workspace {}: lockId mismatch (provided={}, stored={})",
                    workspaceId, lockId, storedLockId);
            return LockResult.notOwner(existingLock != null ? existingLock : getLockInfoFromDb(workspaceId));
        }

        // Atomic delete via Lua script
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(redisKey), currentValue);

        if (result != null && result == 1L) {
            clearLockFromDb(workspaceId);
            log.info("Lock released for workspace {}", workspaceId);
            return LockResult.released();
        }

        // Lock was changed between GET and EVAL (rare race)
        clearLockFromDb(workspaceId);
        log.info("Lock released for workspace {} (already changed)", workspaceId);
        return LockResult.released();
    }

    /**
     * Force-unlock a workspace regardless of who owns the lock.
     * Should only be called by users with admin/force-unlock permission.
     *
     * @param workspaceId the workspace UUID
     * @return LockResult indicating success
     */
    @Transactional
    public LockResult forceReleaseLock(String workspaceId) {
        String redisKey = buildRedisKey(workspaceId);

        log.warn("Force-unlocking workspace {}", workspaceId);
        redisTemplate.delete(redisKey);
        clearLockFromDb(workspaceId);

        return LockResult.released();
    }

    /**
     * Check if a workspace is currently locked.
     * Checks Redis first (source of truth), falls back to DB if Redis key expired.
     *
     * @param workspaceId the workspace UUID
     * @return true if locked and not expired
     */
    public boolean isLocked(String workspaceId) {
        String redisKey = buildRedisKey(workspaceId);
        Boolean hasKey = redisTemplate.hasKey(redisKey);

        if (Boolean.TRUE.equals(hasKey)) {
            return true;
        }

        // Redis key doesn't exist - check if DB still shows locked (stale state)
        Optional<Workspace> workspace = workspaceRepository.findById(UUID.fromString(workspaceId));
        if (workspace.isPresent() && workspace.get().isLocked()) {
            // DB shows locked but Redis expired - clean up stale lock
            log.info("Cleaning up stale lock for workspace {} (Redis expired, DB still locked)", workspaceId);
            clearLockFromDb(workspaceId);
        }
        return false;
    }

    /**
     * Get lock information for a workspace.
     * Combines data from Redis (lock existence) and DB (metadata).
     *
     * @param workspaceId the workspace UUID
     * @return LockInfo if locked, null if not locked
     */
    public LockInfo getLockInfo(String workspaceId) {
        String redisKey = buildRedisKey(workspaceId);
        Object value = redisTemplate.opsForValue().get(redisKey);

        if (value == null) {
            return null;
        }

        // Parse lock value from Redis
        String lockValue = value.toString();
        String lockId = extractLockId(lockValue);
        String lockedBy = extractLockedBy(lockValue);

        // Get additional metadata from DB
        Optional<Workspace> workspace = workspaceRepository.findById(UUID.fromString(workspaceId));
        Instant lockedAt = workspace.map(Workspace::getLockedAt).orElse(null);
        String reason = workspace.map(Workspace::getLockDescription).orElse(null);

        Long ttl = redisTemplate.getExpire(redisKey);
        Instant expiresAt = (ttl != null && ttl > 0) ? Instant.now().plusSeconds(ttl) : null;

        return LockInfo.builder()
                .lockId(lockId)
                .lockedBy(lockedBy)
                .lockedAt(lockedAt)
                .reason(reason)
                .expiresAt(expiresAt)
                .build();
    }

    // --- Private helpers ---

    private void persistLockToDb(String workspaceId, String lockId, String userId, String reason) {
        Workspace workspace = workspaceRepository.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        workspace.setLocked(true);
        workspace.setLockId(lockId);
        workspace.setLockedBy(userId);
        workspace.setLockedAt(Instant.now());
        workspace.setLockDescription(reason);
        workspaceRepository.save(workspace);
    }

    private void clearLockFromDb(String workspaceId) {
        Optional<Workspace> workspaceOpt = workspaceRepository.findById(UUID.fromString(workspaceId));
        workspaceOpt.ifPresent(workspace -> {
            workspace.setLocked(false);
            workspace.setLockId(null);
            workspace.setLockedBy(null);
            workspace.setLockedAt(null);
            workspace.setLockDescription(null);
            workspaceRepository.save(workspace);
        });
    }

    private LockInfo getLockInfoFromDb(String workspaceId) {
        Optional<Workspace> workspace = workspaceRepository.findById(UUID.fromString(workspaceId));
        if (workspace.isPresent() && workspace.get().isLocked()) {
            Workspace ws = workspace.get();
            return LockInfo.builder()
                    .lockId(ws.getLockId())
                    .lockedBy(ws.getLockedBy())
                    .lockedAt(ws.getLockedAt())
                    .reason(ws.getLockDescription())
                    .build();
        }
        return LockInfo.builder()
                .lockId("unknown")
                .lockedBy("unknown")
                .lockedAt(Instant.now())
                .reason("Locked by another process")
                .build();
    }

    private String buildRedisKey(String workspaceId) {
        return LOCK_KEY_PREFIX + workspaceId;
    }

    private String buildLockValue(String lockId, String userId) {
        return lockId + SEPARATOR + userId + SEPARATOR + Instant.now().toString();
    }

    private String extractLockId(String lockValue) {
        String[] parts = lockValue.split(SEPARATOR);
        return parts.length > 0 ? parts[0] : "";
    }

    private String extractLockedBy(String lockValue) {
        String[] parts = lockValue.split(SEPARATOR);
        return parts.length > 1 ? parts[1] : "unknown";
    }
}
