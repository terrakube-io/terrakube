package io.terrakube.api.plugin.streaming;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
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
public class StreamingService {

    static final int DEFAULT_LIVE_TAIL_RECORDS = 5000;

    final StepRepository stepRepository;

    final RedisStreamReader redisStreamReader;

    final JobRepository jobRepository;

    /** Max trailing lines the running-step plain GET returns from the Redis stream. */
    final int liveTailRecords;

    public StreamingService(StepRepository stepRepository, RedisStreamReader redisStreamReader,
                            JobRepository jobRepository,
                            @Value("${io.terrakube.logs.live-tail-records:5000}") int liveTailRecords) {
        this.stepRepository = stepRepository;
        this.redisStreamReader = redisStreamReader;
        this.jobRepository = jobRepository;
        this.liveTailRecords = liveTailRecords;
    }

    public String getCurrentLogs(String stepId, String streamKeySuffix) {
        TextStringBuilder currentLogs = new TextStringBuilder();
        try {
            // findById (not getReferenceById): callers include ScheduleJob's doRunExecution path,
            // which reads Job/Step outside any open Hibernate session - a lazy getReferenceById
            // proxy would throw "no session" the moment a field like getStatus() below is touched.
            Step step = stepRepository.findById(UUID.fromString(stepId)).orElseThrow();
            if (step.getStatus().equals(JobStatus.completed) || step.getStatus().equals(JobStatus.failed)) {
                return "";
            }
            String streamKey = step.getJob().getId() + streamKeySuffix;
            List<MapRecord> streamData = redisStreamReader.readTail(streamKey, liveTailRecords);
            for (MapRecord mapRecord : streamData) {
                StringRecord stringRecord = StringRecord.of(mapRecord);
                currentLogs.appendln(stringRecord.getValue().get("output"));
            }
        } catch (Exception ex) {
            log.error("getCurrentLogs failed for step {}: {}", stepId, ex.getMessage());
        }
        return currentLogs.toString();
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
