package io.terrakube.api.plugin.streaming;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingServiceTest {

    @Mock
    StepRepository stepRepository;

    @Mock
    RedisStreamReader redisStreamReader;

    @Mock
    JobRepository jobRepository;

    @Test
    @Timeout(5)
    void getCurrentLogsReturnsEmptyForTerminalStepWithoutReadingRedis() {
        UUID stepId = UUID.randomUUID();
        Job job = new Job();
        job.setId(7);
        Step completed = new Step();
        completed.setId(stepId);
        completed.setJob(job);
        completed.setStatus(JobStatus.completed);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(completed));

        StreamingService service = new StreamingService(stepRepository, redisStreamReader, jobRepository, 5000);
        String result = service.getCurrentLogs(stepId.toString(), "");

        assertEquals("", result);
        verifyNoInteractions(redisStreamReader);
    }

    @Test
    @Timeout(5)
    void getCurrentLogsReadsBoundedTailForRunningStep() {
        UUID stepId = UUID.randomUUID();
        Job job = new Job();
        job.setId(7);
        Step running = new Step();
        running.setId(stepId);
        running.setJob(job);
        running.setStatus(JobStatus.running);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(running));
        when(redisStreamReader.readTail(eq("7"), anyInt()))
                .thenReturn(List.of(
                        MapRecord.create("7", Map.of("output", "line A")),
                        MapRecord.create("7", Map.of("output", "line B"))));

        StreamingService service = new StreamingService(stepRepository, redisStreamReader, jobRepository, 5000);
        String result = service.getCurrentLogs(stepId.toString(), "");

        assertTrue(result.contains("line A"));
        assertTrue(result.contains("line B"));
        verify(redisStreamReader).readTail(eq("7"), anyInt());
    }

    /** Records the named events and data sent to a context-stream viewer. */
    static class RecordingEmitter extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        final java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean completed;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder sb = new StringBuilder();
            for (org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType d : builder.build()) {
                if (d.getData() instanceof String text) {
                    sb.append(text);
                }
            }
            sent.add(sb.toString().replace("\n", "|"));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    private Job jobWithStatus(JobStatus status) {
        Job job = new Job();
        job.setId(7);
        job.setStatus(status);
        when(jobRepository.findById(7)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    @Timeout(10)
    void contextStreamReportsATrimmedResumeIdBeforeReplayAndEndsWithCompleteWhenTheJobFinishes() throws Exception {
        jobWithStatus(JobStatus.completed);
        when(redisStreamReader.firstEntryId("7-context")).thenReturn(org.springframework.data.redis.connection.stream.RecordId.of("50-0"));
        when(redisStreamReader.readAfter(eq("7-context"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        StreamingService service = new StreamingService(stepRepository, redisStreamReader, jobRepository, 5000);
        RecordingEmitter emitter = new RecordingEmitter();

        service.streamJobContext("7", emitter, org.springframework.data.redis.connection.stream.RecordId.of("10-0"),
                new io.terrakube.api.plugin.context.ContextSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()));

        assertTrue(emitter.sent.get(0).contains("event:gap"), emitter.sent.toString());
        assertTrue(emitter.sent.get(0).contains("\"firstAvailableId\":\"50-0\""));
        assertTrue(emitter.sent.get(0).contains("\"recovery\":\"context\""));
        assertTrue(emitter.sent.get(1).contains("event:end"));
        assertTrue(emitter.sent.get(1).contains("\"reason\":\"complete\""));
        assertTrue(emitter.completed);
    }

    @Test
    @Timeout(10)
    void contextStreamRotatesAfterTheMaximumStreamDurationSoTheClientCanTellRotationFromCompletion() throws Exception {
        StreamingService service = new StreamingService(stepRepository, redisStreamReader, jobRepository, 5000);
        service.maxStreamDuration = java.time.Duration.ZERO;
        RecordingEmitter emitter = new RecordingEmitter();

        service.streamJobContext("7", emitter, org.springframework.data.redis.connection.stream.RecordId.of("0-0"),
                new io.terrakube.api.plugin.context.ContextSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()));

        assertTrue(emitter.sent.get(0).contains("\"reason\":\"rotate\""), emitter.sent.toString());
        assertTrue(emitter.completed);
    }
}
