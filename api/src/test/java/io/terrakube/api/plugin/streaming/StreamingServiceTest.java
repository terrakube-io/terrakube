package io.terrakube.api.plugin.streaming;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingServiceTest {

    @Mock
    StepRepository stepRepository;

    @Mock
    RedisStreamReader redisStreamReader;

    @Test
    @Timeout(5)
    void startsReadingFromTheProvidedResumeId() throws Exception {
        UUID stepId = UUID.randomUUID();
        Job job = new Job();
        job.setId(42);

        Step completedStep = new Step();
        completedStep.setId(stepId);
        completedStep.setJob(job);
        completedStep.setStatus(JobStatus.completed);

        when(stepRepository.findById(stepId)).thenReturn(Optional.of(completedStep));
        when(redisStreamReader.readAfter(eq("42"), eq(RecordId.of("100-0")), any(Duration.class)))
                .thenReturn(Collections.emptyList());

        SseEmitter emitter = new SseEmitter(0L);
        StreamingService streamingService = new StreamingService(null, stepRepository, redisStreamReader);
        streamingService.streamStepLogs(stepId.toString(), emitter, RecordId.of("100-0"));

        verify(redisStreamReader).readAfter(eq("42"), eq(RecordId.of("100-0")), any(Duration.class));
    }

    @Test
    @Timeout(5)
    void nextReadStartsAfterTheLastRecordIdReceived() throws Exception {
        UUID stepId = UUID.randomUUID();
        Job job = new Job();
        job.setId(42);

        Step runningStep = new Step();
        runningStep.setId(stepId);
        runningStep.setJob(job);
        runningStep.setStatus(JobStatus.running);

        Step completedStep = new Step();
        completedStep.setId(stepId);
        completedStep.setJob(job);
        completedStep.setStatus(JobStatus.completed);

        when(stepRepository.findById(stepId))
                .thenReturn(Optional.of(runningStep))
                .thenReturn(Optional.of(completedStep));

        MapRecord record = MapRecord.create("42", Map.of("output", "line 1")).withId(RecordId.of("100-0"));
        when(redisStreamReader.readAfter(eq("42"), eq(RecordId.of("0-0")), any(Duration.class)))
                .thenReturn(List.of(record));
        when(redisStreamReader.readAfter(eq("42"), eq(RecordId.of("100-0")), any(Duration.class)))
                .thenReturn(Collections.emptyList());

        SseEmitter emitter = new SseEmitter(0L);
        StreamingService streamingService = new StreamingService(null, stepRepository, redisStreamReader);
        streamingService.streamStepLogs(stepId.toString(), emitter, RecordId.of("0-0"));

        // Proves the loop advances lastId to the id of the record it just processed - the same value used
        // both for the next Redis read and for the "id:" field sent to the client (SseEmitter internals make
        // the latter unobservable from a unit test: SseEmitter.send(SseEventBuilder) calls
        // super.send(Set<DataWithMediaType>) via invokespecial, which bypasses any subclass override).
        verify(redisStreamReader).readAfter(eq("42"), eq(RecordId.of("100-0")), any(Duration.class));
    }
}
