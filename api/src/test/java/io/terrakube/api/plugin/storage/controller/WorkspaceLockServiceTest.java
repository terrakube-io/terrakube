package io.terrakube.api.plugin.storage.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceLockServiceTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
    private WorkspaceLockService subject;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        subject = new WorkspaceLockService(redisTemplate, 5L);
    }

    @Test
    void getLockInfoReturnsEmptyWhenKeyAbsent() {
        when(valueOps.get("tflock:ws-1")).thenReturn(null);
        assertTrue(subject.getLockInfo("ws-1").isEmpty());
    }

    @Test
    void getLockInfoReturnsValueWhenPresent() {
        when(valueOps.get("tflock:ws-1")).thenReturn("{\"ID\":\"abc\"}");
        Optional<String> info = subject.getLockInfo("ws-1");
        assertTrue(info.isPresent());
        assertEquals("{\"ID\":\"abc\"}", info.get());
    }

    @Test
    void tryAcquireUsesSetIfAbsentWithConfiguredTtl() {
        when(valueOps.setIfAbsent("tflock:ws-1", "{\"ID\":\"abc\"}", 5L, TimeUnit.SECONDS)).thenReturn(true);

        boolean acquired = subject.tryAcquire("ws-1", "{\"ID\":\"abc\"}");

        assertTrue(acquired);
        verify(valueOps).setIfAbsent("tflock:ws-1", "{\"ID\":\"abc\"}", 5L, TimeUnit.SECONDS);
    }

    @Test
    void tryAcquireReturnsFalseWhenLoserOfRace() {
        when(valueOps.setIfAbsent(any(String.class), any(), anyLong(), any())).thenReturn(false);
        assertFalse(subject.tryAcquire("ws-1", "{\"ID\":\"abc\"}"));
    }

    @Test
    void tryAcquireDefensivelyReturnsFalseWhenRedisReturnsNull() {
        // Redis client can return null on connection issues; should not throw NPE.
        when(valueOps.setIfAbsent(any(String.class), any(), anyLong(), any())).thenReturn(null);
        assertFalse(subject.tryAcquire("ws-1", "{\"ID\":\"abc\"}"));
    }

    @Test
    void refreshExtendsTtlAndReturnsTrueWhenKeyExists() {
        when(redisTemplate.expire("tflock:ws-1", 5L, TimeUnit.SECONDS)).thenReturn(true);
        assertTrue(subject.refresh("ws-1"));
        verify(redisTemplate).expire("tflock:ws-1", 5L, TimeUnit.SECONDS);
    }

    @Test
    void refreshReturnsFalseWhenKeyAlreadyExpired() {
        when(redisTemplate.expire("tflock:ws-1", 5L, TimeUnit.SECONDS)).thenReturn(false);
        assertFalse(subject.refresh("ws-1"));
    }

    @Test
    void refreshReturnsFalseDefensivelyWhenRedisReturnsNull() {
        when(redisTemplate.expire("tflock:ws-1", 5L, TimeUnit.SECONDS)).thenReturn(null);
        assertFalse(subject.refresh("ws-1"));
    }

    @Test
    void releaseDeletesKey() {
        subject.release("ws-1");
        verify(redisTemplate).delete("tflock:ws-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseIfMatchesDeletesWhenValueStillMatches() {
        when(redisTemplate.execute(any(RedisScript.class),
                eq(Collections.singletonList("tflock:ws-1")), eq("{\"ID\":\"abc\"}")))
                .thenReturn(1L);

        assertTrue(subject.releaseIfMatches("ws-1", "{\"ID\":\"abc\"}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseIfMatchesReturnsFalseWhenAnotherHolderHasTheLock() {
        // The compare-and-delete found a different value (someone else re-acquired)
        // and deleted nothing — the late unlock must NOT report a removal.
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);

        assertFalse(subject.releaseIfMatches("ws-1", "{\"ID\":\"abc\"}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseIfMatchesReturnsFalseDefensivelyWhenRedisReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

        assertFalse(subject.releaseIfMatches("ws-1", "{\"ID\":\"abc\"}"));
    }

    @Test
    void readLockIdExtractsIdField() {
        assertEquals("abc-123", subject.readLockId("{\"ID\":\"abc-123\",\"Operation\":\"OperationTypeApply\"}"));
    }

    @Test
    void readLockIdReturnsNullForBlankInput() {
        assertNull(subject.readLockId(""));
        assertNull(subject.readLockId("   "));
        assertNull(subject.readLockId(null));
    }

    @Test
    void readLockIdReturnsNullForInvalidJson() {
        assertNull(subject.readLockId("{not json"));
    }

    @Test
    void readLockIdReturnsNullWhenIdMissing() {
        assertNull(subject.readLockId("{\"Operation\":\"x\"}"));
    }
}
