package io.terrakube.executor.service.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogsServiceRedisTest {

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    @Mock
    StreamOperations<String, Object, Object> streamOps;

    @Test
    void sendLogsTrimsTheStreamApproximately() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        ExecutorLogsProperties props = new ExecutorLogsProperties();
        props.setRedisMaxLen(200_000);
        LogsServiceRedis service = new LogsServiceRedis(redisTemplate, props);

        service.sendLogs(42, "step-1", 1, "hello");

        ArgumentCaptor<XAddOptions> opts = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOps).add(eq("42"), any(Map.class), opts.capture());
        assertEquals(200_000L, opts.getValue().getMaxlen());
        assertTrue(opts.getValue().isApproximateTrimming());
    }

    @Test
    void redisFailureIsSwallowedAndNeverThrows() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(String.class), any(Map.class), any(XAddOptions.class)))
                .thenThrow(new RuntimeException("redis down"));
        LogsServiceRedis service = new LogsServiceRedis(redisTemplate, new ExecutorLogsProperties());

        service.sendLogs(42, "step-1", 1, "hello"); // must not throw
    }

    @Test
    void structuredUpdateAlsoTrimsTheContextStream() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        LogsServiceRedis service = new LogsServiceRedis(redisTemplate, new ExecutorLogsProperties());

        service.sendStructuredUpdate(42, "step-1", "{}");

        verify(streamOps).add(eq("42-context"), any(Map.class), any(XAddOptions.class));
    }
}
