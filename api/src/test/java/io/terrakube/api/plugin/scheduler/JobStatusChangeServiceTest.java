package io.terrakube.api.plugin.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.subscription.JobStatusEvent;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.plugin.workspace.WorkspaceJobStatusService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

class JobStatusChangeServiceTest {

    private final WorkspaceJobStatusService workspaceStatus = mock(WorkspaceJobStatusService.class);
    private final JobStatusPublisher publisher = mock(JobStatusPublisher.class);
    private final JobNotificationTrigger notificationTrigger = mock(JobNotificationTrigger.class);
    private final JobStatusChangeService subject =
            new JobStatusChangeService(workspaceStatus, publisher, notificationTrigger);

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    private Job closedJob(JobStatus status) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setLastJobStatus(JobStatus.waitingApproval);
        Organization organization = new Organization();
        organization.setId(organizationId);
        Job job = new Job();
        job.setId(7);
        job.setStatus(status);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        return job;
    }

    @Test
    void updatesTheWorkspacePublishesTheEventAndNotifies() {
        Job job = closedJob(JobStatus.failed);

        subject.afterStatusChanged(job);

        verify(workspaceStatus).refresh(job);
        verify(publisher).publish(new JobStatusEvent(7, workspaceId.toString(), "failed"), organizationId.toString());
        verify(notificationTrigger).notifyStatusChanged(job);
    }

    @Test
    void aFailingStepDoesNotStopTheOthers() {
        Job job = closedJob(JobStatus.cancelled);
        doThrow(new RuntimeException("db")).when(workspaceStatus).refresh(any());
        doThrow(new RuntimeException("redis")).when(publisher).publish(any(), any());

        subject.afterStatusChanged(job);

        verify(notificationTrigger).notifyStatusChanged(job);
    }

    @Test
    void aFailingNotificationDoesNotThrow() {
        Job job = closedJob(JobStatus.failed);
        doThrow(new RuntimeException("outbox")).when(notificationTrigger).notifyStatusChanged(any());

        subject.afterStatusChanged(job);

        verify(workspaceStatus).refresh(job);
        verify(publisher).publish(any(), eq(organizationId.toString()));
    }

    @Test
    void aJobWithoutAWorkspaceOnlyNotifies() {
        Job job = closedJob(JobStatus.failed);
        job.setWorkspace(null);

        subject.afterStatusChanged(job);

        verify(workspaceStatus, never()).refresh(any());
        verify(publisher, never()).publish(any(), any());
        verify(notificationTrigger).notifyStatusChanged(job);
    }

    @Test
    void publishStatusOnlySendsTheLiveEvent() {
        Job job = closedJob(JobStatus.failed);

        subject.publishStatus(job);

        verify(publisher).publish(new JobStatusEvent(7, workspaceId.toString(), "failed"), organizationId.toString());
        verify(workspaceStatus, never()).refresh(any());
        verify(notificationTrigger, never()).notifyStatusChanged(any());
    }

    @Test
    void publishStatusOfAJobWithoutAWorkspaceDoesNothing() {
        Job job = closedJob(JobStatus.failed);
        job.setWorkspace(null);

        subject.publishStatus(job);

        verify(publisher, never()).publish(any(), any());
    }
}
