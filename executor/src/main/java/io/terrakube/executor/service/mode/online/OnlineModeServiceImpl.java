package io.terrakube.executor.service.mode.online;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.Workspace;
import io.terrakube.client.model.response.Response;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/terraform-rs")
@Slf4j
@AllArgsConstructor
public class OnlineModeServiceImpl {

    ExecutorJob executorJob;
    ExecutorCapacityGate executorCapacityGate;
    ApplicationEventPublisher eventPublisher;
    TerrakubeClient terrakubeClient;

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
            updateWorkspaceFolder(terraformJob);
            executorJob.createJob(terraformJob);
        } catch (TaskRejectedException e) {
            // The gate was free, but the single-thread async pool (see SpringAsyncAutoConfiguration)
            // was still tearing down the previous job when this submission landed. Undo the gate
            // acquisition and readiness change we just made and let the API scheduler retry rather
            // than surface this as a 500.
            log.warn("Executor pool rejected job for Organization {} Workspace {}: {}",
                    terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), e.getMessage());
            executorCapacityGate.release();
            publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
            return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new ResponseEntity<>(terraformJob, HttpStatus.ACCEPTED);
    }

    private void updateWorkspaceFolder(TerraformJob terraformJob) {
        if (terrakubeClient != null) {
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
    }

    private void publishReadiness(ReadinessState state) {
        try {
            AvailabilityChangeEvent.publish(eventPublisher, this, state);
        } catch (Exception e) {
            log.error("Failed to publish readiness state {}: {}", state, e.getMessage());
        }
    }
}
