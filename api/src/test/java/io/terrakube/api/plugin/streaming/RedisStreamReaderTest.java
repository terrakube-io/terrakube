package io.terrakube.api.plugin.streaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    @Test
    void readTailReturnsLastRecordsInChronologicalOrder() {
        MapRecord newest = MapRecord.create("42", Map.of("output", "line 3")).withId(RecordId.of("3-0"));
        MapRecord middle = MapRecord.create("42", Map.of("output", "line 2")).withId(RecordId.of("2-0"));
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        // reverseRange yields newest-first; readTail must flip it back to chronological order.
        when(streamOperations.reverseRange(any(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(newest, middle));

        RedisStreamReader reader = new RedisStreamReader(redisTemplate);
        List<MapRecord> result = reader.readTail("42", 5000);

        assertThat(result).containsExactly(middle, newest);
    }

    @Test
    void readTailReturnsEmptyWhenRedisReturnsNull() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.reverseRange(any(), any(Range.class), any(Limit.class))).thenReturn(null);

        RedisStreamReader reader = new RedisStreamReader(redisTemplate);

        assertThat(reader.readTail("42", 5000)).isEmpty();
    }
}
