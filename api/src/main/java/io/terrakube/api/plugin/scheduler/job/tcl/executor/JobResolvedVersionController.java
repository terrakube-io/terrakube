package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import io.terrakube.api.plugin.security.authentication.dex.ActiveStepTokenValidator;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lets the executor record the concrete Terraform/OpenTofu version a job resolved from its workspace's
 * version constraint (issue #3707). The first version written wins and sticks: every later step of the job
 * is dispatched with it, because a plan file can only be read by the exact version that created it.
 *
 * <p>{@code PUT /version/v1/organization/{organizationId}/job/{jobId}} with {@code {"version":"1.16.5"}}:
 * {@code 200} pinned (or already that version), {@code 409} the job is pinned to a different version,
 * {@code 400} not a version number, {@code 403} not the executor service, {@code 404} no such job in that
 * organization, {@code 409} also when the job has already finished.
 * It is a dedicated endpoint, not an Elide update, so recording a version does not run the job lifecycle
 * hooks that reschedule a job.
 */
@Slf4j
@RestController
@RequestMapping("/version/v1")
public class JobResolvedVersionController {

    /** A plain version number: it ends up in download URLs and file paths. */
    static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    public record PinRequest(String version) {
    }

    private final JobRepository jobRepository;
    public JobResolvedVersionController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @PutMapping("/organization/{organizationId}/job/{jobId}")
    @Transactional
    public ResponseEntity<Map<String, String>> pin(Authentication authentication, @PathVariable String organizationId,
                                                   @PathVariable int jobId, @RequestBody PinRequest request) {
        // Recording a version is an executor-daemon operation. A step runner token is exposed to user
        // scripts, so only the executor service token may do it.
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || InternalTokenClassifier.classify(jwt.getTokenAttributes()) != InternalTokenType.EXECUTOR_SERVICE) {
            log.warn("Forbidden attempt to pin a version on job {} from a non-executor principal", jobId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String version = request == null ? null : request.version();
        if (version == null || version.length() > 64 || !VERSION.matcher(version).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "version must be a version number such as 1.9.5"));
        }
        Optional<Job> job = jobRepository.findById(jobId);
        if (job.isEmpty() || job.get().getOrganization() == null
                || !job.get().getOrganization().getId().toString().equals(organizationId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (ActiveStepTokenValidator.isTerminalStatus(job.get().getStatus())) {
            return finished(jobId, job.get());
        }
        if (jobRepository.pinResolvedTerraformVersion(jobId, UUID.fromString(organizationId), version) == 1) {
            return ResponseEntity.ok(Map.of("version", version));
        }
        // Either a different version was pinned first, or the job finished since the check above.
        Job current = jobRepository.findById(jobId).orElse(job.get());
        String existing = current.getResolvedTerraformVersion();
        if (existing == null) {
            return finished(jobId, current);
        }
        log.info("Job {} is already pinned to {}, not {}", jobId, existing, version);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("version", existing));
    }

    private ResponseEntity<Map<String, String>> finished(int jobId, Job job) {
        log.warn("Cannot pin a version on job {}: it is {}", jobId, job.getStatus());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Cannot pin a version on a job that is " + job.getStatus()));
    }
}
