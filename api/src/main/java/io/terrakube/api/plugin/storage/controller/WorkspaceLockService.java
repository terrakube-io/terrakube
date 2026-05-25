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
 * raw lock JSON body terraform sends, keyed by workspace id, with a TTL so a
 * crashed agent never leaves a workspace locked forever.
 */
@Service
@Slf4j
public class WorkspaceLockService {

    private static final String KEY_PREFIX = "tflock:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long lockTtlMinutes;

    public WorkspaceLockService(RedisTemplate<String, Object> redisTemplate,
                                @Value("${io.terrakube.api.http-backend.lock-ttl-minutes:30}") long lockTtlMinutes) {
        this.redisTemplate = redisTemplate;
        this.lockTtlMinutes = lockTtlMinutes;
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
                .setIfAbsent(key(workspaceId), lockInfoJson, lockTtlMinutes, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(acquired);
    }

    public void refresh(String workspaceId) {
        redisTemplate.expire(key(workspaceId), lockTtlMinutes, TimeUnit.MINUTES);
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
