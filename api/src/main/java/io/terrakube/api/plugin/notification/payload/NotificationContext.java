package io.terrakube.api.plugin.notification.payload;

import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationMessageStyle;

/**
 * {@code jobStatus} and {@code cascadeStatus} are mutually exclusive - a context describes either
 * one job's status transition or one cascade's outcome, never both. {@code jobId}/{@code runUrl}
 * are populated either way: for a cascade event they identify the job whose completion started
 * it, same "Job: #N" context a job event already shows.
 */
public record NotificationContext(
        String organizationName,
        String workspaceName,
        int jobId,
        JobStatus jobStatus,
        String runUrl,
        String commitId,
        String failureReason,
        String configurationName,
        String workspaceUrl,
        NotificationMessageStyle messageStyle,
        RunCascadeStatus cascadeStatus,
        String cascadeSummary) {
}
