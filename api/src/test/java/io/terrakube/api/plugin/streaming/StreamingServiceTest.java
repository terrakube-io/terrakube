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
}
