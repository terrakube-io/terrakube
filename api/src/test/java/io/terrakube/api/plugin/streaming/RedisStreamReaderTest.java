package io.terrakube.api.plugin.streaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStreamReaderTest {

    @Mock
    RedisTemplate redisTemplate;

    @Mock
    StreamOperations streamOperations;

    @Test
    void returnsRecordsFromRedis() {
        MapRecord record = MapRecord.create("42", Collections.singletonMap("output", "line 1"));
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        RedisStreamReader reader = new RedisStreamReader(redisTemplate);
        List<MapRecord> result = reader.readAfter("42", RecordId.of("0-0"), Duration.ofSeconds(2));

        assertThat(result).containsExactly(record);
    }

    @Test
    void returnsEmptyListWhenRedisReturnsNull() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(null);

        RedisStreamReader reader = new RedisStreamReader(redisTemplate);
        List<MapRecord> result = reader.readAfter("42", RecordId.of("0-0"), Duration.ofSeconds(2));

        assertThat(result).isEmpty();
    }
}
