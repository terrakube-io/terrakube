package io.terrakube.api.plugin.logs;

import io.terrakube.api.plugin.state.model.logs.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogsServiceTest {

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    @Mock
    StreamOperations<String, Object, Object> streamOps;

    private Log logEntry(int jobId) {
        Log log = new Log();
        log.setJobId(jobId);
        log.setStepId("step-1");
        log.setLineNumber(1);
        log.setOutput("line");
        return log;
    }

    private LogsService service() {
        LogsProperties props = new LogsProperties();
        props.setRedisMaxLen(200_000);
        return new LogsService(redisTemplate, props);
    }

    @Test
    void appendLogsTrimsEachStreamApproximatelyInOnePipeline() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(inv -> {
                    inv.<SessionCallback<?>>getArgument(0).execute(redisTemplate);
                    return List.of();
                });

        service().appendLogs(List.of(logEntry(1), logEntry(1), logEntry(2)));

        verify(redisTemplate, times(1)).executePipelined(any(SessionCallback.class));
        ArgumentCaptor<XAddOptions> opts = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOps, times(3)).add(any(String.class), any(Map.class), opts.capture());
        assertEquals(200_000L, opts.getValue().getMaxlen());
        assertTrue(opts.getValue().isApproximateTrimming());
        verify(streamOps, times(2)).add(eq("1"), any(Map.class), any(XAddOptions.class));
        verify(streamOps, times(1)).add(eq("2"), any(Map.class), any(XAddOptions.class));
    }

    @Test
    void appendLogsInClusterModeWritesWithoutPipeline() {
        JedisConnectionFactory clusterFactory = mock(JedisConnectionFactory.class);
        when(clusterFactory.isRedisClusterAware()).thenReturn(true);
        when(redisTemplate.getConnectionFactory()).thenReturn(clusterFactory);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);

        service().appendLogs(List.of(logEntry(1), logEntry(2)));

        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
        verify(streamOps, times(2)).add(any(String.class), any(Map.class), any(XAddOptions.class));
    }
}
