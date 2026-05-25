package io.terrakube.api.plugin.storage.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
        when(valueOps.setIfAbsent("tflock:ws-1", "{\"ID\":\"abc\"}", 5L, TimeUnit.MINUTES)).thenReturn(true);

        boolean acquired = subject.tryAcquire("ws-1", "{\"ID\":\"abc\"}");

        assertTrue(acquired);
        verify(valueOps).setIfAbsent("tflock:ws-1", "{\"ID\":\"abc\"}", 5L, TimeUnit.MINUTES);
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
    void refreshExtendsTtlOnKey() {
        subject.refresh("ws-1");
        verify(redisTemplate).expire("tflock:ws-1", 5L, TimeUnit.MINUTES);
    }

    @Test
    void releaseDeletesKey() {
        subject.release("ws-1");
        verify(redisTemplate).delete("tflock:ws-1");
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
