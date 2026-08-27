package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobLogBroadcasterTest {

    @Mock
    RedisStreamReader reader;

    @Mock
    JobStatusCache jobStatusCache;

    private LogsProperties props() {
        LogsProperties p = new LogsProperties();
        p.setSseJobIdleTimeout(Duration.ofMillis(20));
        p.setSseMaxStreamDuration(Duration.ofSeconds(10));
        return p;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MapRecord rec(String stepId, String output, String id) {
        return MapRecord.create("42", Map.of("stepId", stepId, "output", output)).withId(RecordId.of(id));
    }

    @Test
    @Timeout(10)
    void fansOneStreamOutToTwoSubscribersFilteredByStepId() {
        when(reader.readAfter(eq("42"), any(RecordId.class), any(Duration.class)))
                .thenReturn(List.of(rec("stepA", "a-line", "1-0"), rec("stepB", "b-line", "2-0")))
                .thenReturn(List.of());
        when(jobStatusCache.isTerminal("42")).thenReturn(false).thenReturn(true);

        RecordingEmitter a = new RecordingEmitter();
        RecordingEmitter b = new RecordingEmitter();
        AtomicInteger onEmpty = new AtomicInteger();
        JobLogBroadcaster broadcaster =
                new JobLogBroadcaster("42", reader, jobStatusCache, props(), onEmpty::incrementAndGet);
        broadcaster.subscribe("stepA", a, RecordId.of("0-0"));
        broadcaster.subscribe("stepB", b, RecordId.of("0-0"));
        broadcaster.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> a.completed && b.completed);
        assertEquals(List.of("a-line"), a.data);
        assertEquals(List.of("b-line"), b.data);
        assertEquals(1, onEmpty.get());
    }

    @Test
    @Timeout(10)
    void loopExitsAndFiresOnEmptyWhenLastSubscriberLeaves() {
        // reader/jobStatusCache default to empty-list / false - the loop exits on the empty
        // subscriber set before it needs either.
        RecordingEmitter a = new RecordingEmitter();
        AtomicInteger onEmpty = new AtomicInteger();
        JobLogBroadcaster broadcaster =
                new JobLogBroadcaster("42", reader, jobStatusCache, props(), onEmpty::incrementAndGet);
        JobLogBroadcaster.Subscription sub = broadcaster.subscribe("stepA", a, RecordId.of("0-0"));
        broadcaster.start();
        sub.close();

        await().atMost(Duration.ofSeconds(5)).until(() -> onEmpty.get() == 1);
        assertEquals(0, broadcaster.subscriberCount());
    }

    @Test
    @Timeout(10)
    void completesAllEmittersWhenJobBecomesTerminal() {
        when(reader.readAfter(eq("42"), any(RecordId.class), any(Duration.class))).thenReturn(List.of());
        when(jobStatusCache.isTerminal("42")).thenReturn(true);

        RecordingEmitter a = new RecordingEmitter();
        JobLogBroadcaster broadcaster =
                new JobLogBroadcaster("42", reader, jobStatusCache, props(), () -> { });
        broadcaster.subscribe("stepA", a, RecordId.of("0-0"));
        broadcaster.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> a.completed);
    }

    /** Captures the {@code data:} payloads sent to an emitter and its completion state. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> data = new CopyOnWriteArrayList<>();
        volatile boolean completed = false;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder sb = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType d : builder.build()) {
                if (d.getData() instanceof String s) {
                    sb.append(s);
                }
            }
            for (String line : sb.toString().split("\n")) {
                if (line.startsWith("data:")) {
                    data.add(line.substring("data:".length()));
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completed = true;
        }
    }
}
