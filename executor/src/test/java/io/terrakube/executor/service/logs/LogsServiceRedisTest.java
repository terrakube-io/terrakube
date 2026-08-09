package io.terrakube.executor.service.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogsServiceRedisTest {

    @Mock
    RedisTemplate redisTemplate;

    @Mock
    StreamOperations streamOperations;

    @Test
    void sendStructuredUpdateWritesToTheContextSuffixedStream() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        LogsServiceRedis subject = new LogsServiceRedis(redisTemplate);

        subject.sendStructuredUpdate(42, "step-1", "{\"changes\":[]}");

        Map<String, String> expectedStreamData = new LinkedHashMap<>();
        expectedStreamData.put("jobId", "42");
        expectedStreamData.put("stepId", "step-1");
        expectedStreamData.put("output", "{\"changes\":[]}");
        verify(streamOperations).add(eq("42-context"), eq(expectedStreamData));
    }

    @Test
    void sendLogsSwallowsARedisFailureInsteadOfPropagatingIt() {
        // sendLogs runs as a callback inside the terraform-spring-boot-starter's own
        // process-output-reading loop - letting a Redis exception escape here would abort the
        // whole terraform run, not just drop a log line.
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        doThrow(new RuntimeException("connection refused")).when(streamOperations).add(any(), any(Map.class));
        LogsServiceRedis subject = new LogsServiceRedis(redisTemplate);

        subject.sendLogs(42, "step-1", 1, "some output");
    }

    @Test
    void sendStructuredUpdateSwallowsARedisFailureInsteadOfPropagatingIt() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        doThrow(new RuntimeException("connection refused")).when(streamOperations).add(any(), any(Map.class));
        LogsServiceRedis subject = new LogsServiceRedis(redisTemplate);

        subject.sendStructuredUpdate(42, "step-1", "{\"changes\":[]}");
    }
}
