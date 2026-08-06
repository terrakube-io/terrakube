package io.terrakube.executor.service.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
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
}
