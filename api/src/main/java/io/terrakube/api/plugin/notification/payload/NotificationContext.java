package io.terrakube.api.plugin.notification.payload;

import io.terrakube.api.plugin.cost.InfracostSummary;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationMessageStyle;

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
        InfracostSummary costSummary) {

    public NotificationContext(
            String organizationName,
            String workspaceName,
            int jobId,
            JobStatus jobStatus,
            String runUrl,
            String commitId,
            String failureReason,
            String configurationName,
            String workspaceUrl,
            NotificationMessageStyle messageStyle) {
        this(organizationName, workspaceName, jobId, jobStatus, runUrl, commitId, failureReason,
                configurationName, workspaceUrl, messageStyle, null);
    }
}
