package io.terrakube.api.plugin.context;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;

/**
 * Makes a mocked {@link RedisTemplate} hold {@link ContextWriteLock}s in a map, so context writes work
 * without Redis. Other keys keep the mocks' default answers.
 */
public final class InMemoryContextWriteLock {

    private InMemoryContextWriteLock() {
    }

    @SuppressWarnings("unchecked")
    public static void stub(RedisTemplate<String, Object> redisTemplate, ValueOperations<String, Object> valueOperations) {
        Map<String, Object> locks = new ConcurrentHashMap<>();
        // doAnswer, and valueOperations fully stubbed before opsForValue() hands it out: a live Quartz
        // thread can call these mocks while they are being stubbed.
        lenient().doAnswer(invocation -> locks.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null)
                .when(valueOperations).setIfAbsent(startsWith(ContextWriteLock.KEY_PREFIX), any(), any(Duration.class));
        lenient().doAnswer(invocation -> locks.remove(invocation.<List<String>>getArgument(1).get(0), invocation.getArgument(2)) ? 1L : 0L)
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
    }
}
