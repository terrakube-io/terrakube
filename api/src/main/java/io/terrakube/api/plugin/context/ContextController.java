package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import com.yahoo.elide.core.security.User;
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
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

import io.terrakube.api.plugin.security.job.JobLogAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/context/v1")
public class ContextController {
    private static final Set<JobStatus> CONTEXT_WRITABLE_JOB_STATUSES = EnumSet.of(
            JobStatus.pending,
            JobStatus.waitingApproval,
            JobStatus.approved,
            JobStatus.queue,
            JobStatus.running,
            JobStatus.completed,
            JobStatus.noChanges);

    private static final String PENDING_BODY = "{\"structuredOutputStatus\":{\"state\":\"PENDING\"}}";

    private final StorageTypeService storageTypeService;

    private final JobRepository jobRepository;

    private final ContextSanitizer contextSanitizer;

    private final StreamingService streamingService;

    private final ContextStorageMetrics contextStorageMetrics;

    private final ContextReadService contextReadService;

    private final ContextProperties contextProperties;

    private final io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService;

    private final JobLogAccessService jobLogAccessService;

    private final AuthenticatedUser authenticatedUser;

    @Autowired
    public ContextController(
            StorageTypeService storageTypeService,
            JobRepository jobRepository,
            ContextSanitizer contextSanitizer,
            StreamingService streamingService,
            ContextStorageMetrics contextStorageMetrics,
            ContextReadService contextReadService,
            ContextProperties contextProperties,
            io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService,
            JobLogAccessService jobLogAccessService,
            AuthenticatedUser authenticatedUser) {
        this.storageTypeService = storageTypeService;
        this.jobRepository = jobRepository;
        this.contextSanitizer = contextSanitizer;
        this.streamingService = streamingService;
        this.contextStorageMetrics = contextStorageMetrics;
        this.contextReadService = contextReadService;
        this.contextProperties = contextProperties;
        this.policyEvaluationService = policyEvaluationService;
        this.jobLogAccessService = jobLogAccessService;
        this.authenticatedUser = authenticatedUser;
    }

    public ContextController(
            StorageTypeService storageTypeService,
            JobRepository jobRepository,
            ContextSanitizer contextSanitizer,
            StreamingService streamingService,
            ContextStorageMetrics contextStorageMetrics,
            ContextReadService contextReadService,
            ContextProperties contextProperties,
            io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService,
            JobLogAccessService jobLogAccessService) {
        this(storageTypeService, jobRepository, contextSanitizer, streamingService,
                contextStorageMetrics, contextReadService, contextProperties, policyEvaluationService, jobLogAccessService, null);
    }

    public ContextController(
            StorageTypeService storageTypeService,
            JobRepository jobRepository,
            ContextSanitizer contextSanitizer,
            StreamingService streamingService,
            ContextStorageMetrics contextStorageMetrics,
            ContextReadService contextReadService,
            ContextProperties contextProperties,
            io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService,
            AuthenticatedUser authenticatedUser) {
        this(storageTypeService, jobRepository, contextSanitizer, streamingService,
                contextStorageMetrics, contextReadService, contextProperties, policyEvaluationService, null, authenticatedUser);
    }

    public ContextController(
            StorageTypeService storageTypeService,
            JobRepository jobRepository,
            ContextSanitizer contextSanitizer,
            StreamingService streamingService,
            ContextStorageMetrics contextStorageMetrics,
            ContextReadService contextReadService,
            ContextProperties contextProperties,
            io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService) {
        this(storageTypeService, jobRepository, contextSanitizer, streamingService,
                contextStorageMetrics, contextReadService, contextProperties, policyEvaluationService, null, null);
    }

    public ResponseEntity<String> getContext(int jobId) {
        return getContext(jobId, (Principal) null);
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getContext(@PathVariable("jobId") int jobId, Principal principal) {
        Authentication authentication = principal instanceof Authentication a ? a : null;
        if (jobLogAccessService != null) {
            JobLogAccessService.LogAccessResult access = jobLogAccessService.checkJobAccess(authentication, jobId);
            if (access == JobLogAccessService.LogAccessResult.NOT_FOUND) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            if (access == JobLogAccessService.LogAccessResult.FORBIDDEN) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        // The executor merges its output into what it reads here and writes it back, so a stale
        // per-pod cache entry would overwrite newer data; only UI polling is served from the cache.
        boolean executor = principal != null && authenticatedUser != null && authenticatedUser.isServiceAccountInternal(new User(principal));
        String context;
        try {
            context = executor ? contextReadService.readFresh(jobId) : contextReadService.read(jobId);
        } catch (RuntimeException e) {
            log.warn("Controlled context-read failure for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }

        // A missing object is "not persisted yet", distinct from an empty plan and from an outage.
        if (context == null || context.isBlank()) {
            return new ResponseEntity<>(PENDING_BODY, HttpStatus.OK);
        }

        try {
            return new ResponseEntity<>(contextSanitizer.sanitize(context), HttpStatus.OK);
        } catch (IOException e) {
            log.warn("Controlled failure sanitizing context for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }
    }

    private ResponseEntity<String> unavailable() {
        int retryAfter = contextProperties.getRetryAfterSeconds();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(retryAfter))
                .body("{\"structuredOutputStatus\":{\"state\":\"UNAVAILABLE\"},\"retryAfterSeconds\":" + retryAfter + "}");
    }

    public ResponseEntity<String> saveContext(int jobId, String context) {
        return saveContext(jobId, context, (Principal) null);
    }

    @PostMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<String> saveContext(@PathVariable("jobId") int jobId, @RequestBody String context, Principal principal) {
        Authentication authentication = principal instanceof Authentication a ? a : null;
        if (jobLogAccessService != null && !jobLogAccessService.canWriteContext(authentication, jobId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

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
        // Refresh the read cache so a Job Details page open right after the write sees this snapshot.
        contextReadService.invalidate(jobId, savedContext);
        if (policyEvaluationService != null) {
            policyEvaluationService.processPolicyEvaluationContext(jobId, sanitizedContext);
        }
        return new ResponseEntity<>(savedContext, HttpStatus.OK);
    }

    public SseEmitter streamContext(String jobId, String lastEventId) {
        return streamContext(jobId, lastEventId, (Principal) null);
    }

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamContext(
            @PathVariable("jobId") String jobId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            Principal principal) {
        Authentication authentication = principal instanceof Authentication a ? a : null;
        if (jobLogAccessService != null) {
            int intJobId;
            try {
                intJobId = Integer.parseInt(jobId);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid jobId");
            }
            JobLogAccessService.LogAccessResult access = jobLogAccessService.checkJobAccess(authentication, intJobId);
            if (access == JobLogAccessService.LogAccessResult.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
            }
            if (access == JobLogAccessService.LogAccessResult.FORBIDDEN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
        }

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
