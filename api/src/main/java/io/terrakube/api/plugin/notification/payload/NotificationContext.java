package io.terrakube.api.plugin.notification.payload;

import io.terrakube.api.rs.job.JobStatus;

public record NotificationContext(
        String organizationName,
        String workspaceName,
        int jobId,
        JobStatus jobStatus,
        String runUrl,
        String commitId,
        String failureReason) {
}
