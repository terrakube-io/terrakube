package io.terrakube.api.plugin.streaming;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.terrakube.api.plugin.context.ContextSanitizer;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
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

    JobRepository jobRepository;

    public String getCurrentLogs(String stepId, String streamKeySuffix){
        TextStringBuilder currentLogs = new TextStringBuilder();
        try {
            // findById (not getReferenceById): callers include ScheduleJob's doRunExecution path,
            // which reads Job/Step outside any open Hibernate session - a lazy getReferenceById
            // proxy would throw "no session" the moment a field like getStatus() below is touched.
            Step step = stepRepository.findById(UUID.fromString(stepId)).orElseThrow();
            if(!step.getStatus().equals(JobStatus.completed) && !step.getStatus().equals(JobStatus.failed)) {
                String streamKey = step.getJob().getId() + streamKeySuffix;
                List<MapRecord> streamData = redisTemplate.opsForStream().read(StreamOffset.fromStart(streamKey), StreamOffset.latest(streamKey));
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
    public void streamStepLogsAsync(String stepId, SseEmitter emitter, RecordId resumeFrom, String streamKeySuffix) {
        streamStepLogs(stepId, emitter, resumeFrom, streamKeySuffix);
    }

    public void streamStepLogs(String stepId, SseEmitter emitter, RecordId resumeFrom, String streamKeySuffix) {
        try {
            UUID id = UUID.fromString(stepId);
            // findById (not getReferenceById) because this loop runs on a separate @Async thread with no
            // active Hibernate session - a lazy getReferenceById proxy would throw LazyInitializationException
            // the moment any field (including the eagerly-mapped job association) is accessed here.
            Step step = stepRepository.findById(id).orElseThrow();
            String streamKey = String.valueOf(step.getJob().getId()) + streamKeySuffix;
            RecordId lastId = resumeFrom;
            int emptyReads = 0;

            while (true) {
                List<MapRecord> records = redisStreamReader.readAfter(streamKey, lastId, Duration.ofSeconds(2));

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
                    emitter.send(SseEmitter.event().id(lastId.getValue()).data(stringRecord.getValue().get("output")));
                }
            }
        } catch (IOException e) {
            log.info("SSE client disconnected for step {}", stepId);
        } catch (Exception e) {
            log.error("Error streaming logs for step {}: {}", stepId, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    @Async
    public void streamJobContextAsync(String jobId, SseEmitter emitter, RecordId resumeFrom, ContextSanitizer contextSanitizer) {
        streamJobContext(jobId, emitter, resumeFrom, contextSanitizer);
    }

    public void streamJobContext(String jobId, SseEmitter emitter, RecordId resumeFrom, ContextSanitizer contextSanitizer) {
        try {
            String streamKey = jobId + "-context";
            RecordId lastId = resumeFrom;
            int emptyReads = 0;

            while (true) {
                List<MapRecord> records = redisStreamReader.readAfter(streamKey, lastId, Duration.ofSeconds(2));

                if (records.isEmpty()) {
                    emptyReads++;
                    Job job = jobRepository.findById(Integer.parseInt(jobId)).orElseThrow();
                    if (isTerminal(job.getStatus())) {
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
                    String sanitized = contextSanitizer.sanitize(stringRecord.getValue().get("output"));
                    emitter.send(SseEmitter.event().id(lastId.getValue()).data(sanitized));
                }
            }
        } catch (IOException e) {
            log.info("SSE client disconnected for job {}", jobId);
        } catch (Exception e) {
            log.error("Error streaming context for job {}: {}", jobId, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private boolean isTerminal(JobStatus status) {
        return status == JobStatus.completed || status == JobStatus.failed || status == JobStatus.cancelled;
    }
}
