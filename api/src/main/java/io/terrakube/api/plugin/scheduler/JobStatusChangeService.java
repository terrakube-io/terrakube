package io.terrakube.api.plugin.scheduler;

import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.subscription.JobStatusEvent;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.plugin.workspace.WorkspaceJobStatusService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything that has to follow a job status change made outside an Elide request (#3727).
 * Elide's {@code JobManageHook} and {@code JobNotificationHook} only see changes that come through
 * the API, so a status set by the scheduler, a sweep or a bulk update would otherwise leave
 * {@code workspace.lastJobStatus} stale, the live pages un-notified and no notification sent.
 * Call this right after the job's new status has been saved.
 *
 * <p>Each step is best effort and independent: a failure to publish or notify must not stop the
 * status change itself, or the other steps.
 */
@Slf4j
@Service
@AllArgsConstructor
public class JobStatusChangeService {

    private final WorkspaceJobStatusService workspaceJobStatusService;
    private final JobStatusPublisher jobStatusPublisher;
    private final JobNotificationTrigger jobNotificationTrigger;

    public void afterStatusChanged(Job job) {
        Workspace workspace = job.getWorkspace();
        if (workspace == null) {
            log.info("Job {} has no workspace, skipping workspace status and live update", job.getId());
        } else {
            try {
                workspaceJobStatusService.refresh(job);
            } catch (RuntimeException e) {
                log.error("Could not update the last job status of workspace {} for job {}",
                        workspace.getId(), job.getId(), e);
            }
            publishStatus(job);
        }
        try {
            jobNotificationTrigger.notifyStatusChanged(job);
        } catch (RuntimeException e) {
            log.error("Could not send status notifications for job {}", job.getId(), e);
        }
    }

    /** Only the live status event, for open pages: no workspace write and no notification. */
    public void publishStatus(Job job) {
        Workspace workspace = job.getWorkspace();
        if (workspace == null || job.getOrganization() == null) {
            return;
        }
        try {
            jobStatusPublisher.publish(
                    new JobStatusEvent(job.getId(), workspace.getId().toString(), job.getStatus().name()),
                    job.getOrganization().getId().toString());
        } catch (RuntimeException e) {
            log.error("Could not publish the status of job {}", job.getId(), e);
        }
    }
}
