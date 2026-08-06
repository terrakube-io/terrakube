package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.subscription.JobStatusEvent;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JobManageHookTest {

    @Mock
    ScheduleJobService scheduleJobService;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    JobStatusPublisher jobStatusPublisher;

    @Test
    void publishesOnUpdate() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(new Organization());

        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);

        Job job = new Job();
        job.setId(42);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.running);

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher);
        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(jobStatusPublisher).publish(new JobStatusEvent(42, workspaceId.toString(), "running"), organizationId.toString());
    }

    @Test
    void publishesOnCreate() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(new Organization());

        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);

        Job job = new Job();
        job.setId(43);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.pending);

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(jobStatusPublisher).publish(new JobStatusEvent(43, workspaceId.toString(), "pending"), organizationId.toString());
    }
}
