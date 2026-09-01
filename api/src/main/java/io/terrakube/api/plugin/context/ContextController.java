package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/context/v1")
@AllArgsConstructor
public class ContextController {
    private static final Set<JobStatus> CONTEXT_WRITABLE_JOB_STATUSES = EnumSet.of(
            JobStatus.pending,
            JobStatus.waitingApproval,
            JobStatus.approved,
            JobStatus.queue,
            JobStatus.running,
            JobStatus.completed,
            JobStatus.noChanges);

    private final StorageTypeService storageTypeService;

    private final JobRepository jobRepository;

    private final ContextSanitizer contextSanitizer;

    private final StreamingService streamingService;

    private final ContextStorageMetrics contextStorageMetrics;

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getContext(@PathVariable("jobId") int jobId) {
        try {
            String context = contextStorageMetrics.time("read", () -> storageTypeService.getContext(jobId));
            if (context == null || context.isBlank()) {
                context = "{}";
            }
            return new ResponseEntity<>(contextSanitizer.sanitize(context), HttpStatus.OK);
        } catch (IOException | RuntimeException e) {
            log.warn("Controlled failure reading context for job {}: {}", jobId, e.getMessage());
            return new ResponseEntity<>("{}", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @PostMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<String> saveContext(@PathVariable("jobId") int jobId, @RequestBody String context) {
        String sanitizedContext;
        try {
            sanitizedContext = contextSanitizer.sanitize(context);
        } catch (JacksonException e) {
            log.warn("Invalid context payload for job {}", jobId, e);
            return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
        } catch (IOException e) {
            log.warn("Controlled failure sanitizing context for job {}: {}", jobId, e.getMessage());
            return new ResponseEntity<>("{}", HttpStatus.SERVICE_UNAVAILABLE);
        }

        Optional<Job> jobOptional = jobRepository.findById(jobId);
        if (jobOptional.isEmpty()) {
            log.warn("Cannot save context for missing job {}", jobId);
            return new ResponseEntity<>("{}", HttpStatus.NOT_FOUND);
        }

        Job job = jobOptional.get();
        if (!CONTEXT_WRITABLE_JOB_STATUSES.contains(job.getStatus())) {
            log.warn("Cannot save context for job {} with status {}", jobId, job.getStatus());
            return new ResponseEntity<>("{}", HttpStatus.CONFLICT);
        }

        String savedContext;
        try {
            String contextToSave = sanitizedContext;
            savedContext = contextStorageMetrics.time("write", () -> storageTypeService.saveContext(jobId, contextToSave));
        } catch (IOException | RuntimeException e) {
            log.warn("Controlled failure saving context for job {}: {}", jobId, e.getMessage());
            return new ResponseEntity<>("{}", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new ResponseEntity<>(savedContext, HttpStatus.OK);
    }

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamContext(
            @PathVariable("jobId") String jobId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        streamingService.streamJobContextAsync(jobId, emitter, parseResumeId(lastEventId), contextSanitizer);
        return emitter;
    }

    private RecordId parseResumeId(String lastEventId) {
        if (!StringUtils.hasText(lastEventId)) {
            return RecordId.of("0-0");
        }
        try {
            return RecordId.of(lastEventId);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unparseable Last-Event-ID '{}': {}", lastEventId, e.getMessage());
            return RecordId.of("0-0");
        }
    }
}
