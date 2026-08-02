package io.terrakube.api.plugin.streaming;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.apache.commons.text.TextStringBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class StreamingService {

    RedisTemplate redisTemplate;

    StepRepository stepRepository;

    RedisStreamReader redisStreamReader;

    public String getCurrentLogs(String stepId){
        TextStringBuilder currentLogs = new TextStringBuilder();
        try {
            Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
            if(!step.getStatus().equals(JobStatus.completed) && !step.getStatus().equals(JobStatus.failed)) {
                List<MapRecord> streamData = redisTemplate.opsForStream().read(StreamOffset.fromStart(String.valueOf(step.getJob().getId())), StreamOffset.latest(String.valueOf(step.getJob().getId())));
                for (MapRecord mapRecord : streamData) {
                    StringRecord stringRecord = StringRecord.of(mapRecord);
                    String output = stringRecord.getValue().get("output");
                    currentLogs.appendln(output);
                }
                log.info("Logs Size: {}", currentLogs.size());
            }
        } catch (Exception ex ){
            log.error(ex.getMessage());

        }
        return currentLogs.toString();
    }

    @Async
    public void streamStepLogsAsync(String stepId, SseEmitter emitter) {
        streamStepLogs(stepId, emitter);
    }

    public void streamStepLogs(String stepId, SseEmitter emitter) {
        try {
            UUID id = UUID.fromString(stepId);
            // findById (not getReferenceById) because this loop runs on a separate @Async thread with no
            // active Hibernate session - a lazy getReferenceById proxy would throw LazyInitializationException
            // the moment any field (including the eagerly-mapped job association) is accessed here.
            Step step = stepRepository.findById(id).orElseThrow();
            String jobId = String.valueOf(step.getJob().getId());
            RecordId lastId = RecordId.of("0-0");
            int emptyReads = 0;

            while (true) {
                List<MapRecord> records = redisStreamReader.readAfter(jobId, lastId, Duration.ofSeconds(2));

                if (records.isEmpty()) {
                    emptyReads++;
                    step = stepRepository.findById(id).orElseThrow();
                    if (isTerminal(step.getStatus())) {
                        emitter.complete();
                        return;
                    }
                    if (emptyReads % 8 == 0) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    }
                    continue;
                }

                emptyReads = 0;
                for (MapRecord record : records) {
                    lastId = record.getId();
                    StringRecord stringRecord = StringRecord.of(record);
                    emitter.send(stringRecord.getValue().get("output"));
                }
            }
        } catch (IOException e) {
            log.info("SSE client disconnected for step {}", stepId);
        } catch (Exception e) {
            log.error("Error streaming logs for step {}: {}", stepId, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private boolean isTerminal(JobStatus status) {
        return status == JobStatus.completed || status == JobStatus.failed || status == JobStatus.cancelled;
    }
}
