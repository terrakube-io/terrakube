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

    /** Up to {@code count} records after {@code lastId}, without blocking. */
    @SuppressWarnings("unchecked")
    public List<MapRecord> readAfterPage(String streamKey, RecordId lastId, int count) {
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.from(lastId));
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        List<MapRecord> records = redisTemplate.opsForStream().read(options, offset);
        return records == null ? Collections.emptyList() : records;
    }

    /** Oldest retained record id, or {@code null} when empty. */
    @SuppressWarnings("unchecked")
    public RecordId firstEntryId(String streamKey) {
        List<MapRecord> first = redisTemplate.opsForStream().range(streamKey, Range.unbounded(), Limit.limit().count(1));
        return first == null || first.isEmpty() ? null : first.get(0).getId();
    }

    /** Newest record id, or {@code null} when empty. */
    @SuppressWarnings("unchecked")
    public RecordId lastEntryId(String streamKey) {
        List<MapRecord> last = redisTemplate.opsForStream().reverseRange(streamKey, Range.unbounded(), Limit.limit().count(1));
        return last == null || last.isEmpty() ? null : last.get(0).getId();
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
