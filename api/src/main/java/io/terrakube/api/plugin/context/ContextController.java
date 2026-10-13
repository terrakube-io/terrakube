package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.elide.core.security.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.plugin.streaming.StreamingService;

import io.terrakube.api.plugin.security.job.JobLogAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;

@Slf4j
@RestController
@RequestMapping("/context/v1")
public class ContextController {
    private static final String PENDING_BODY = "{\"structuredOutputStatus\":{\"state\":\"PENDING\"}}";

    private final ContextSanitizer contextSanitizer;

    private final StreamingService streamingService;

    private final ContextReadService contextReadService;

    private final ContextWriteService contextWriteService;

    private final ContextProperties contextProperties;

    private final io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService;

    private final JobLogAccessService jobLogAccessService;

    private final AuthenticatedUser authenticatedUser;

    private final ObjectMapper objectMapper;

    @Autowired
    public ContextController(
            ContextSanitizer contextSanitizer,
            StreamingService streamingService,
            ContextReadService contextReadService,
            ContextWriteService contextWriteService,
            ContextProperties contextProperties,
            io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService,
            JobLogAccessService jobLogAccessService,
            AuthenticatedUser authenticatedUser,
            ObjectMapper objectMapper) {
        this.contextSanitizer = contextSanitizer;
        this.streamingService = streamingService;
        this.contextReadService = contextReadService;
        this.contextWriteService = contextWriteService;
        this.contextProperties = contextProperties;
        this.policyEvaluationService = policyEvaluationService;
        this.jobLogAccessService = jobLogAccessService;
        this.authenticatedUser = authenticatedUser;
        this.objectMapper = objectMapper;
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

        // The executor's only read is the apply step seeding from the plan output, which must see the
        // latest document rather than a stale per-pod cache entry; only UI polling is served from the cache.
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
    public ResponseEntity<String> saveContext(@PathVariable("jobId") int jobId, @RequestBody String context, Principal principal) {
        Authentication authentication = principal instanceof Authentication a ? a : null;
        if (jobLogAccessService != null && !jobLogAccessService.canWriteContext(authentication, jobId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ObjectNode incoming;
        try {
            if (!(objectMapper.readTree(context) instanceof ObjectNode document)) {
                log.warn("Context payload for job {} is not a JSON object", jobId);
                return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
            }
            incoming = document;
        } catch (JacksonException e) {
            log.warn("Invalid context payload for job {}", jobId, e);
            return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
        }

        ContextWriteService.ReplaceResult result;
        try {
            result = contextWriteService.replace(jobId, incoming);
        } catch (ResponseStatusException e) {
            return new ResponseEntity<>("{}", e.getStatusCode());
        } catch (IOException | RuntimeException e) {
            // Includes a job row lock that could not be taken in time.
            log.warn("Controlled failure saving context for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }
        // Runs after the write transaction, so the job row lock is not held during policy processing.
        if (policyEvaluationService != null && result.policyChanged()) {
            policyEvaluationService.processPolicyEvaluationContext(jobId, result.saved());
        }
        return new ResponseEntity<>(result.saved(), HttpStatus.OK);
    }

    public ResponseEntity<String> patchContext(int jobId, String patch) {
        return patchContext(jobId, patch, (Principal) null);
    }

    /**
     * Sets individual context entries ({@code {"revision": n, "set": {path: value|null}}}). An entry
     * older than or equal to the one stored is skipped, so a retried or reordered write is a no-op.
     */
    @PostMapping(value = "/{jobId}/patch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> patchContext(@PathVariable("jobId") int jobId, @RequestBody String patch, Principal principal) {
        Authentication authentication = principal instanceof Authentication a ? a : null;
        if (jobLogAccessService != null && !jobLogAccessService.canWriteContext(authentication, jobId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Validated before the job row is locked.
        ContextPatchApplier.Patch parsed;
        try {
            parsed = ContextPatchApplier.parse(objectMapper.readTree(patch), System.currentTimeMillis());
        } catch (JacksonException | InvalidContextPatchException e) {
            log.warn("Invalid context patch for job {}", jobId);
            return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
        }

        ContextWriteService.PatchResult result;
        try {
            result = contextWriteService.patch(jobId, parsed);
        } catch (ResponseStatusException e) {
            return new ResponseEntity<>("{}", e.getStatusCode());
        } catch (IOException | RuntimeException e) {
            log.warn("Controlled failure patching context for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }
        if (policyEvaluationService != null && result.applied().contains("policyEvaluation")) {
            policyEvaluationService.processPolicyEvaluationContext(jobId, result.context());
        }
        return new ResponseEntity<>("{\"applied\":" + result.applied().size() + ",\"skipped\":" + result.skipped() + "}", HttpStatus.OK);
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
