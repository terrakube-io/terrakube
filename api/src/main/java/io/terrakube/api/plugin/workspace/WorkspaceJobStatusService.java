package io.terrakube.api.plugin.workspace;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Keeps {@code workspace.lastJobStatus} as the status of the run that holds the workspace's queue.
 * <p>
 * A workspace runs one job at a time and newer jobs wait behind an unfinished one. The status of whichever job
 * changed last is not what the workspace is doing: a run that waits for approval is still what the workspace
 * waits for when a newer run is created behind it as {@code pending}. So the status shown is the one of the
 * oldest job that is not finished and, when every job is finished, the one of the job that changed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceJobStatusService {

    private final JobRepository jobRepository;
    private final WorkspaceRepository workspaceRepository;

    /**
     * The status the workspace shows after {@code changed} was created or updated. Other jobs are read from the
     * database, the changed job from memory, so it does not matter whether its own row is saved yet.
     */
    public JobStatus statusFor(Job changed) {
        Workspace workspace = changed.getWorkspace();
        if (workspace == null) {
            return changed.getStatus();
        }
        boolean changedHoldsTheQueue = changed.getStatus().holdsTheQueue();
        return jobRepository
                .findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(workspace, changed.getId(), JobStatus.holdingTheQueue())
                .filter(other -> !changedHoldsTheQueue || other.getId() < changed.getId())
                .map(Job::getStatus)
                .orElse(changed.getStatus());
    }

    /** Sets status and date on the workspace of {@code changed} and saves it. */
    public void refresh(Job changed) {
        Workspace workspace = changed.getWorkspace();
        if (workspace == null) {
            return;
        }
        JobStatus status = statusFor(changed);
        log.info("Updating last status for workspace {} to {} (job {} is {})", workspace.getName(), status,
                changed.getId(), changed.getStatus());
        workspace.setLastJobStatus(status);
        workspace.setLastJobDate(new Date(System.currentTimeMillis()));
        workspaceRepository.save(workspace);
    }
}
