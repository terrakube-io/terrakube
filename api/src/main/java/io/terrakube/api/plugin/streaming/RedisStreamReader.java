package io.terrakube.api.plugin.streaming;

import lombok.AllArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Component
@AllArgsConstructor
public class RedisStreamReader {

    RedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    public List<MapRecord> readAfter(String streamKey, RecordId lastId, Duration blockDuration) {
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.from(lastId));
        StreamReadOptions options = StreamReadOptions.empty().count(100).block(blockDuration);

        List<MapRecord> records = redisTemplate.opsForStream().read(options, offset);

        return records == null ? Collections.emptyList() : records;
    }
}
