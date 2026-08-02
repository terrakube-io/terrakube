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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingServiceTest {

    @Mock
    StepRepository stepRepository;

    @Mock
    RedisStreamReader redisStreamReader;

    @Test
    @Timeout(5)
    void streamsNewRecordsThenCompletesWhenStepIsTerminal() throws Exception {
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

        MapRecord record = MapRecord.create("42", Map.of("output", "line 1"));
        when(redisStreamReader.readAfter(eq("42"), any(RecordId.class), any(Duration.class)))
                .thenReturn(List.of(record))
                .thenReturn(Collections.emptyList());

        List<String> sentPayloads = new ArrayList<>();
        SseEmitter emitter = new SseEmitter(0L) {
            @Override
            public void send(Object object) {
                sentPayloads.add(String.valueOf(object));
            }
        };

        StreamingService streamingService = new StreamingService(null, stepRepository, redisStreamReader);
        streamingService.streamStepLogs(stepId.toString(), emitter);

        assertThat(sentPayloads).containsExactly("line 1");
    }
}
