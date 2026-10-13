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
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    @Test
    @Timeout(5)
    void streamJobContextCompletesWhenJobEndsRejected() {
        Job job = new Job();
        job.setId(7);
        job.setStatus(JobStatus.rejected);
        when(redisStreamReader.readAfter(eq("7-context"), any(RecordId.class), any(Duration.class)))
                .thenReturn(List.of());
        when(jobRepository.findById(7)).thenReturn(Optional.of(job));
        SseEmitter emitter = mock(SseEmitter.class);

        new StreamingService(stepRepository, redisStreamReader, jobRepository, 5000)
                .streamJobContext("7", emitter, RecordId.of("0-0"), null);

        verify(emitter).complete();
    }
}
