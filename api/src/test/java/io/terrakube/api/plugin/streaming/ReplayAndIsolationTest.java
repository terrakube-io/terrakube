package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Replay/live handoff, trimmed history and slow-viewer isolation against an in-memory stand-in for the
 * Redis stream (ids are {@code <n>-0}, appended in order, optionally trimmed from the front).
 */
class ReplayAndIsolationTest {

    private static final String JOB = "42";

    private final FakeStream stream = new FakeStream();
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final RedisStreamReader reader = Mockito.mock(RedisStreamReader.class);
    private final JobStatusCache statusCache = Mockito.mock(JobStatusCache.class);
    private final LogsProperties properties = new LogsProperties();

    @BeforeEach
    void setUp() {
        properties.setSseJobIdleTimeout(Duration.ofMillis(5));
        properties.setSseMaxStreamDuration(Duration.ofSeconds(60));
        properties.setSseReplayPageSize(100);
        when(reader.readAfter(eq(JOB), any(RecordId.class), any(Duration.class)))
                .thenAnswer(i -> stream.readAfter(i.getArgument(1), 100, i.<Duration>getArgument(2)));
        when(reader.readAfterPage(eq(JOB), any(RecordId.class), anyInt()))
                .thenAnswer(i -> stream.readAfter(i.getArgument(1), i.getArgument(2), Duration.ZERO));
        when(reader.readTail(eq(JOB), anyInt())).thenAnswer(i -> stream.tail(i.getArgument(1)));
        when(reader.firstEntryId(JOB)).thenAnswer(i -> stream.firstId());
        when(reader.lastEntryId(JOB)).thenAnswer(i -> stream.lastId());
        when(statusCache.isTerminal(anyString())).thenAnswer(i -> terminal.get());
    }

    private JobLogBroadcaster broadcaster() {
        return new JobLogBroadcaster(JOB, reader, statusCache, properties, () -> { });
    }

    private Recorder subscribe(JobLogBroadcaster broadcaster, String step, String resumeFrom) {
        Recorder emitter = new Recorder();
        broadcaster.subscribe(step, emitter, RecordId.of(resumeFrom));
        broadcaster.start();
        return emitter;
    }

    private static List<String> expected(String step, int from, int toInclusive) {
        List<String> lines = new ArrayList<>();
        for (int n = from; n <= toInclusive; n++) {
            lines.add(step + "-" + n);
        }
        return lines;
    }

    @Test
    @Timeout(20)
    void historyLargerThanOneReplayPageIsDeliveredInOrderExactlyOnce() {
        for (int n = 1; n <= 2_550; n++) {
            stream.append("stepA", "stepA-" + n);
            stream.append("stepB", "stepB-" + n);
        }
        terminal.set(true);
        properties.setSseFreshViewerTailRecords(0);

        Recorder viewer = subscribe(broadcaster(), "stepA", "0-0");

        await().atMost(Duration.ofSeconds(15)).until(() -> viewer.completed);
        assertEquals(expected("stepA", 1, 2_550), viewer.data);
        assertEquals("complete", viewer.endReason());
        assertTrue(viewer.idsStrictlyIncreasing());
    }

    @Test
    @Timeout(30)
    void viewersJoiningWhileOutputIsBeingPublishedMissNothingAndSeeNothingTwice() throws Exception {
        for (int n = 1; n <= 300; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        JobLogBroadcaster shared = broadcaster();
        Recorder first = subscribe(shared, "stepA", "0-0");

        List<Recorder> latecomers = new CopyOnWriteArrayList<>();
        List<Integer> resumePoints = new CopyOnWriteArrayList<>();
        Thread publisher = Thread.ofPlatform().start(() -> {
            for (int n = 301; n <= 6_000; n++) {
                stream.append("stepA", "stepA-" + n);
                if (n % 450 == 0) {
                    // A viewer reconnecting with the last id it saw, in the middle of live output.
                    int resumeAfter = n - 200;
                    resumePoints.add(resumeAfter);
                    latecomers.add(subscribe(shared, "stepA", resumeAfter + "-0"));
                }
            }
            terminal.set(true);
        });
        publisher.join();

        await().atMost(Duration.ofSeconds(20)).until(() ->
                first.completed && latecomers.stream().allMatch(r -> r.completed));
        assertEquals(expected("stepA", 1, 6_000), first.data);
        for (int i = 0; i < latecomers.size(); i++) {
            assertEquals(expected("stepA", resumePoints.get(i) + 1, 6_000), latecomers.get(i).data,
                    "viewer resuming after " + resumePoints.get(i));
            assertTrue(latecomers.get(i).idsStrictlyIncreasing());
        }
    }

    @Test
    @Timeout(20)
    void resumingBeforeTheOldestRetainedRecordReportsTheUnavailableRangeFirst() {
        for (int n = 1; n <= 100; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        stream.trimTo(51);
        terminal.set(true);

        Recorder behind = subscribe(broadcaster(), "stepA", "10-0");
        await().atMost(Duration.ofSeconds(10)).until(() -> behind.completed);

        assertEquals(1, behind.gaps.size());
        assertTrue(behind.gaps.get(0).contains("\"afterId\":\"10-0\""), behind.gaps.get(0));
        assertTrue(behind.gaps.get(0).contains("\"firstAvailableId\":\"51-0\""), behind.gaps.get(0));
        assertTrue(behind.gaps.get(0).contains("trimmed"));
        assertEquals(expected("stepA", 51, 100), behind.data);
        assertEquals(0, behind.events.indexOf("gap"), "the gap is reported before any replayed output");

        Recorder current = subscribe(broadcaster(), "stepA", "60-0");
        await().atMost(Duration.ofSeconds(10)).until(() -> current.completed);
        assertTrue(current.gaps.isEmpty());
        assertEquals(expected("stepA", 61, 100), current.data);

        Recorder fresh = subscribe(broadcaster(), "stepA", "0-0");
        await().atMost(Duration.ofSeconds(10)).until(() -> fresh.completed);
        assertTrue(fresh.gaps.isEmpty());
    }

    @Test
    @Timeout(30)
    void aStalledViewerIsDisconnectedWithoutDelayingOtherViewers() throws Exception {
        properties.setSseSubscriberMaxQueuedBytes(20_000);
        JobLogBroadcaster shared = broadcaster();

        CountDownLatch release = new CountDownLatch(1);
        Recorder stalled = new Recorder() {
            @Override
            public void send(SseEventBuilder builder) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.send(builder);
            }
        };
        JobLogBroadcaster.Subscription stalledSubscription = shared.subscribe("stepA", stalled, RecordId.of("0-0"));
        Recorder healthy = subscribe(shared, "stepA", "0-0");
        // Wait until it is a live viewer: one stalled while still replaying history just has its
        // overflow re-read later (bounded memory, nothing lost), so it is not the case under test.
        await().atMost(Duration.ofSeconds(5)).until(stalledSubscription::isLive);

        long begin = System.nanoTime();
        for (int n = 1; n <= 3_000; n++) {
            stream.append("stepA", "stepA-" + n + "-" + "x".repeat(60));
            if (n % 25 == 0) {
                // Paced so a viewer that is reading keeps within its budget; only the stalled one cannot.
                Thread.sleep(1);
            }
        }
        terminal.set(true);

        await().atMost(Duration.ofSeconds(15)).until(() -> healthy.completed);
        assertEquals(3_000, healthy.data.size());
        assertTrue(Duration.ofNanos(System.nanoTime() - begin).toSeconds() < 15);
        await().atMost(Duration.ofSeconds(5)).until(() -> shared.slowViewersDisconnected() == 1);

        // Once the client reads again it is told to resume by cursor rather than silently losing output.
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> stalled.completed);
        assertEquals("overflow", stalled.endReason());
        assertTrue(stalled.idsStrictlyIncreasing());
    }

    @Test
    @Timeout(30)
    void liveOutputArrivingDuringALongReplayDoesNotCountAsASlowViewer() throws Exception {
        properties.setSseSubscriberMaxQueuedBytes(3_000);
        for (int n = 1; n <= 2_000; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        JobLogBroadcaster shared = broadcaster();
        AtomicInteger sent = new AtomicInteger();
        Recorder viewer = new Recorder() {
            @Override
            public void send(SseEventBuilder builder) {
                super.send(builder);
                if (sent.incrementAndGet() % 50 == 0) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        shared.subscribe("stepA", viewer, RecordId.of("0-0"));
        shared.start();
        for (int n = 2_001; n <= 4_000; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        terminal.set(true);

        await().atMost(Duration.ofSeconds(20)).until(() -> viewer.completed);
        assertEquals(0, shared.slowViewersDisconnected());
        assertEquals(expected("stepA", 1, 4_000), viewer.data);
    }

    @Test
    @Timeout(20)
    void aFreshViewerOfAHugeRunGetsTheRecentTailAndANoticeButAResumingViewerGetsEverythingAfterItsCursor() {
        properties.setSseFreshViewerTailRecords(1_000);
        for (int n = 1; n <= 12_000; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        terminal.set(true);

        Recorder fresh = subscribe(broadcaster(), "stepA", "0-0");
        Recorder resuming = subscribe(broadcaster(), "stepA", "2000-0");
        await().atMost(Duration.ofSeconds(15)).until(() -> fresh.completed && resuming.completed);

        assertEquals(expected("stepA", 11_001, 12_000), fresh.data);
        assertEquals(1, fresh.gaps.size());
        assertTrue(fresh.gaps.get(0).contains("history-limited"));
        assertTrue(fresh.gaps.get(0).contains("\"firstAvailableId\":\"11001-0\""));
        assertEquals(0, fresh.events.indexOf("gap"));
        assertTrue(fresh.idsStrictlyIncreasing());

        assertEquals(expected("stepA", 2_001, 12_000), resuming.data);
        assertTrue(resuming.gaps.isEmpty());
    }

    @Test
    @Timeout(20)
    void aFreshViewerOfARunShorterThanTheTailIsNotToldAnythingIsMissing() {
        properties.setSseFreshViewerTailRecords(1_000);
        for (int n = 1; n <= 400; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        terminal.set(true);

        Recorder fresh = subscribe(broadcaster(), "stepA", "0-0");
        await().atMost(Duration.ofSeconds(10)).until(() -> fresh.completed);

        assertTrue(fresh.gaps.isEmpty());
        assertEquals(expected("stepA", 1, 400), fresh.data);
    }

    @Test
    @Timeout(30)
    void replayPageReadsNeverExceedThePodWidePermitCount() throws Exception {
        for (int n = 1; n <= 1_500; n++) {
            stream.append("stepA", "stepA-" + n);
        }
        terminal.set(true);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(reader.readAfterPage(eq(JOB), any(RecordId.class), anyInt())).thenAnswer(i -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(2);
                return stream.readAfter(i.getArgument(1), i.getArgument(2), Duration.ZERO);
            } finally {
                inFlight.decrementAndGet();
            }
        });

        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(3);
        JobLogBroadcaster shared = new JobLogBroadcaster(JOB, reader, statusCache, properties, () -> { }, permits);
        List<Recorder> viewers = new ArrayList<>();
        for (int v = 0; v < 12; v++) {
            Recorder viewer = new Recorder();
            shared.subscribe("stepA", viewer, RecordId.of("0-0"));
            viewers.add(viewer);
        }
        shared.start();

        await().atMost(Duration.ofSeconds(25)).until(() -> viewers.stream().allMatch(r -> r.completed));
        assertTrue(peak.get() <= 3, "peak concurrent replay reads was " + peak.get());
        for (Recorder viewer : viewers) {
            assertEquals(expected("stepA", 1, 1_500), viewer.data);
        }
    }

    @Test
    @Timeout(20)
    void theFanOutStartPositionIsReadBeforeTheFirstViewerStartsReplaying() {
        stream.append("stepA", "stepA-1");
        terminal.set(true);

        subscribe(broadcaster(), "stepA", "0-0");

        // Reading it afterwards could miss a record appended between the viewer's replay and the read.
        org.mockito.InOrder order = Mockito.inOrder(reader);
        order.verify(reader).lastEntryId(JOB);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                order.verify(reader, Mockito.atLeastOnce()).readAfterPage(eq(JOB), any(RecordId.class), anyInt()));
    }

    @Test
    @Timeout(20)
    void aRedisFailureReadingTheStartPositionEndsTheStreamInsteadOfFailingTheRequest() {
        when(reader.lastEntryId(JOB)).thenThrow(new IllegalStateException("redis down"));
        when(reader.readAfterPage(eq(JOB), any(RecordId.class), anyInt())).thenReturn(List.of());
        AtomicBoolean released = new AtomicBoolean();

        JobLogBroadcaster failing = new JobLogBroadcaster(JOB, reader, statusCache, properties, () -> released.set(true));
        Recorder viewer = subscribe(failing, "stepA", "0-0");

        await().atMost(Duration.ofSeconds(5)).until(() -> viewer.completed);
        assertEquals("rotate", viewer.endReason());
        assertTrue(released.get(), "the broadcaster is released so the next viewer builds a fresh one");
    }

    /** In-memory Redis-stream stand-in. */
    static final class FakeStream {
        private final List<MapRecord<String, String, String>> records = new ArrayList<>();
        private long next = 1;

        synchronized void append(String step, String output) {
            records.add(MapRecord.create(JOB, Map.of("stepId", step, "output", output)).withId(RecordId.of(next++ + "-0")));
        }

        synchronized void trimTo(long firstKept) {
            records.removeIf(r -> r.getId().getTimestamp() < firstKept);
        }

        synchronized List<MapRecord> tail(int count) {
            return new ArrayList<>(records.subList(Math.max(0, records.size() - count), records.size()));
        }

        synchronized RecordId firstId() {
            return records.isEmpty() ? null : records.get(0).getId();
        }

        synchronized RecordId lastId() {
            return records.isEmpty() ? null : records.get(records.size() - 1).getId();
        }

        List<MapRecord> readAfter(RecordId after, int count, Duration block) {
            List<MapRecord> result = snapshotAfter(after, count);
            if (result.isEmpty() && !block.isZero()) {
                try {
                    Thread.sleep(Math.min(block.toMillis(), 5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                result = snapshotAfter(after, count);
            }
            return result;
        }

        private synchronized List<MapRecord> snapshotAfter(RecordId after, int count) {
            List<MapRecord> result = new ArrayList<>();
            for (MapRecord<String, String, String> record : records) {
                if (StreamIds.compare(record.getId(), after) > 0) {
                    result.add(record);
                    if (result.size() >= count) {
                        break;
                    }
                }
            }
            return result;
        }
    }

    /** Records what a client would receive: unnamed data, named events, and the ids it was sent. */
    static class Recorder extends SseEmitter {
        final List<String> data = new CopyOnWriteArrayList<>();
        final List<String> ids = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> gaps = new CopyOnWriteArrayList<>();
        final List<String> ends = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        Recorder() {
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
            String name = null;
            String id = null;
            String payload = null;
            for (String line : sb.toString().split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6);
                } else if (line.startsWith("id:")) {
                    id = line.substring(3);
                } else if (line.startsWith("data:")) {
                    payload = line.substring(5);
                }
            }
            if (payload == null) {
                return;
            }
            if (name == null) {
                data.add(payload);
                ids.add(id);
            } else {
                events.add(name);
                (name.equals("gap") ? gaps : ends).add(payload);
            }
        }

        String endReason() {
            if (ends.isEmpty()) {
                return null;
            }
            String payload = ends.get(ends.size() - 1);
            return payload.replaceAll(".*\"reason\":\"([a-z]+)\".*", "$1");
        }

        boolean idsStrictlyIncreasing() {
            long previous = 0;
            for (String id : ids) {
                long current = Long.parseLong(id.substring(0, id.indexOf('-')));
                if (current <= previous) {
                    return false;
                }
                previous = current;
            }
            return true;
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
