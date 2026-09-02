package io.terrakube.api.plugin.streaming;

import lombok.AllArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * All records after {@code lastId}, without blocking. Used for the one-shot catch-up a new SSE
     * subscriber needs before it joins the job's shared broadcaster loop.
     */
    @SuppressWarnings("unchecked")
    public List<MapRecord> readAfterOnce(String streamKey, RecordId lastId) {
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.from(lastId));
        StreamReadOptions options = StreamReadOptions.empty().count(10_000);
        List<MapRecord> records = redisTemplate.opsForStream().read(options, offset);
        return records == null ? Collections.emptyList() : records;
    }

    /**
     * The last {@code count} records of the stream, in chronological order. Used by the running-step
     * plain GET so it never scans the whole (potentially huge) stream from the start.
     */
    @SuppressWarnings("unchecked")
    public List<MapRecord> readTail(String streamKey, int count) {
        List<MapRecord> newestFirst = redisTemplate.opsForStream()
                .reverseRange(streamKey, Range.unbounded(), Limit.limit().count(count));
        if (newestFirst == null || newestFirst.isEmpty()) {
            return Collections.emptyList();
        }
        List<MapRecord> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological;
    }
}
