package io.terrakube.executor.service.mode.online;

import feign.FeignException;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.workspace.Workspace;
import io.terrakube.client.model.response.Response;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.JobContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/terraform-rs")
@Slf4j
public class OnlineModeServiceImpl {

    private static final Set<String> DISPATCHABLE_JOB_STATUSES = Set.of(
            "pending",
            "approved",
            "queue",
            "running",
            "rejected"
    );

    private enum VerificationResult {
        VALID,
        MISMATCH,
        LOOKUP_ERROR
    }

    ExecutorJob executorJob;
    ExecutorCapacityGate executorCapacityGate;
    ApplicationEventPublisher eventPublisher;
    TerrakubeClient terrakubeClient;

    public OnlineModeServiceImpl(
            ExecutorJob executorJob,
            ExecutorCapacityGate executorCapacityGate,
            ApplicationEventPublisher eventPublisher,
            TerrakubeClient terrakubeClient) {
        this.executorJob = Objects.requireNonNull(executorJob, "executorJob must not be null");
        this.executorCapacityGate = Objects.requireNonNull(executorCapacityGate, "executorCapacityGate must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.terrakubeClient = Objects.requireNonNull(terrakubeClient, "terrakubeClient must not be null");
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TerraformJob> terraformJob(@RequestBody TerraformJob terraformJob) {
        log.debug("Received terraform job {}", terraformJob);

        if (!executorCapacityGate.tryAcquire()) {
            log.warn("Rejecting job for Organization {} Workspace {}: this pod is already running a job",
                    terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
            return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
        }

        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        try {
            JobContextHolder.set(terraformJob);
            try {
                VerificationResult verification = verifyJobIdentity(terraformJob);
                if (verification == VerificationResult.MISMATCH) {
                    releaseGateAndAcceptTraffic();
                    return new ResponseEntity<>(HttpStatus.FORBIDDEN);
                }
                if (verification == VerificationResult.LOOKUP_ERROR) {
                    releaseGateAndAcceptTraffic();
                    return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
                }
                updateWorkspaceFolder(terraformJob);
            } finally {
                JobContextHolder.clear();
            }
            executorJob.createJob(terraformJob);
        } catch (TaskRejectedException e) {
            // The gate was free, but the single-thread async pool (see SpringAsyncAutoConfiguration)
            // was still tearing down the previous job when this submission landed. Undo the gate
            // acquisition and readiness change we just made and let the API scheduler retry rather
            // than surface this as a 500.
            log.warn("Executor pool rejected job for Organization {} Workspace {}: {}",
                    terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), e.getMessage());
            releaseGateAndAcceptTraffic();
            return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new ResponseEntity<>(terraformJob, HttpStatus.ACCEPTED);
    }

    private void releaseGateAndAcceptTraffic() {
        executorCapacityGate.release();
        publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
    }

    private VerificationResult verifyJobIdentity(TerraformJob terraformJob) {
        if (terraformJob == null
                || isBlank(terraformJob.getOrganizationId())
                || isBlank(terraformJob.getWorkspaceId())
                || isBlank(terraformJob.getJobId())) {
            log.warn("Rejecting terraform job with missing organizationId, workspaceId, or jobId");
            return VerificationResult.MISMATCH;
        }
        try {
            ResponseWithInclude<Job, Step> jobResponse = terrakubeClient.getJobById(
                    terraformJob.getOrganizationId(), terraformJob.getJobId());
            if (jobResponse == null || jobResponse.getData() == null) {
                log.warn("Rejecting job {}: not found in organization {}",
                        terraformJob.getJobId(), terraformJob.getOrganizationId());
                return VerificationResult.MISMATCH;
            }
            Job job = jobResponse.getData();
            if (job.getId() != null && !terraformJob.getJobId().equals(job.getId())) {
                log.warn("Rejecting job {}: API returned mismatched job id {}",
                        terraformJob.getJobId(), job.getId());
                return VerificationResult.MISMATCH;
            }
            String actualWorkspaceId = job.getRelationships() != null
                    && job.getRelationships().getWorkspace() != null
                    && job.getRelationships().getWorkspace().getData() != null
                    ? job.getRelationships().getWorkspace().getData().getId()
                    : null;
            if (!terraformJob.getWorkspaceId().equals(actualWorkspaceId)) {
                log.warn("Rejecting job {}: workspaceId {} does not match authoritative workspace {}",
                        terraformJob.getJobId(), terraformJob.getWorkspaceId(), actualWorkspaceId);
                return VerificationResult.MISMATCH;
            }
            if (job.getRelationships() != null
                    && job.getRelationships().getOrganization() != null
                    && job.getRelationships().getOrganization().getData() != null
                    && job.getRelationships().getOrganization().getData().getId() != null
                    && !terraformJob.getOrganizationId().equals(
                            job.getRelationships().getOrganization().getData().getId())) {
                log.warn("Rejecting job {}: organizationId {} does not match authoritative organization {}",
                        terraformJob.getJobId(), terraformJob.getOrganizationId(),
                        job.getRelationships().getOrganization().getData().getId());
                return VerificationResult.MISMATCH;
            }
            String status = job.getAttributes() != null ? job.getAttributes().getStatus() : null;
            if (status == null || !DISPATCHABLE_JOB_STATUSES.contains(status)) {
                log.warn("Rejecting job {}: authoritative status '{}' is not dispatchable",
                        terraformJob.getJobId(), status);
                return VerificationResult.MISMATCH;
            }
            return VerificationResult.VALID;
        } catch (FeignException.NotFound e) {
            log.warn("Rejecting job {}: not found in organization {} (HTTP 404)",
                    terraformJob.getJobId(), terraformJob.getOrganizationId());
            return VerificationResult.MISMATCH;
        } catch (Exception e) {
            log.warn("Deferring job {} in organization {}: failed to verify job identity against API: {}",
                    terraformJob.getJobId(), terraformJob.getOrganizationId(), e.getMessage());
            return VerificationResult.LOOKUP_ERROR;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void updateWorkspaceFolder(TerraformJob terraformJob) {
        try {
            Response<Workspace> workspaceResponse = terrakubeClient.getWorkspaceById(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
            if (workspaceResponse != null && workspaceResponse.getData() != null && workspaceResponse.getData().getAttributes() != null) {
                String trustedFolder = workspaceResponse.getData().getAttributes().getFolder();
                if (trustedFolder != null) {
                    terraformJob.setFolder(trustedFolder);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch trusted workspace folder for workspace {}: {}", terraformJob.getWorkspaceId(), e.getMessage());
        }
    }

    private void publishReadiness(ReadinessState state) {
        try {
            AvailabilityChangeEvent.publish(eventPublisher, this, state);
        } catch (Exception e) {
            log.error("Failed to publish readiness state {}: {}", state, e.getMessage());
        }
    }
}
