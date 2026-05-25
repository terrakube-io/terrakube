package io.terrakube.api.plugin.storage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed storage for the per-workspace terraform state lock. Stores the
 * raw lock JSON body terraform sends, keyed by workspace id, with a short TTL.
 * The executor running the operation drives a heartbeat that calls
 * {@link #refresh(String)} every minute so a healthy run holds the lock for
 * the duration of even very long applies, while a crashed or partitioned
 * executor lets the lock expire and frees the workspace for the next run.
 */
@Service
@Slf4j
public class WorkspaceLockService {

    private static final String KEY_PREFIX = "tflock:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long lockTtlSeconds;

    public WorkspaceLockService(RedisTemplate<String, Object> redisTemplate,
                                @Value("${io.terrakube.api.http-backend.lock-ttl-seconds:300}") long lockTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.lockTtlSeconds = lockTtlSeconds;
    }

    public Optional<String> getLockInfo(String workspaceId) {
        Object value = redisTemplate.opsForValue().get(key(workspaceId));
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    public Optional<String> getLockId(String workspaceId) {
        return getLockInfo(workspaceId).map(this::readLockId);
    }

    /**
     * Atomic SET ... NX EX. Returns true when this caller won the race, false
     * when another holder already owns the lock.
     */
    public boolean tryAcquire(String workspaceId, String lockInfoJson) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key(workspaceId), lockInfoJson, lockTtlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Extend the TTL on an existing lock. Driven by the executor's heartbeat
     * thread once a minute; long applies stay locked as long as the executor
     * keeps the heartbeat alive. Returns {@code true} only when the key
     * existed and the TTL was extended — a {@code false} return tells the
     * caller (and the executor's heartbeat loop) that the lock has been lost.
     */
    public boolean refresh(String workspaceId) {
        Boolean refreshed = redisTemplate.expire(key(workspaceId), lockTtlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(refreshed);
    }

    public void release(String workspaceId) {
        redisTemplate.delete(key(workspaceId));
    }

    public String readLockId(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode parsed = objectMapper.readTree(body);
            JsonNode id = parsed.path("ID");
            return id.isMissingNode() || id.isNull() ? null : id.asText(null);
        } catch (IOException e) {
            log.warn("Could not parse lock body as JSON: {}", e.getMessage());
            return null;
        }
    }

    private String key(String workspaceId) {
        return KEY_PREFIX + workspaceId;
    }
}
