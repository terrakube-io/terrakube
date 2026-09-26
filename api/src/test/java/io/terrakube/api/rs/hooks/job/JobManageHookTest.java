package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobManageHookTest {

    @Test
    void createHookSchedulesJobContextEvenWhenWorkspaceStatusUpdateFails() throws Exception {
        ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        JobStatusPublisher jobStatusPublisher = mock(JobStatusPublisher.class);
        AddressRepository addressRepository = mock(AddressRepository.class);
        when(workspaceRepository.save(any()))
                .thenThrow(new EntityNotFoundException("Unable to find Job with id 123"));

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("workspace-a");
        Job job = new Job();
        job.setId(1);
        job.setStatus(JobStatus.pending);
        job.setOrganization(organization);
        job.setWorkspace(workspace);

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT,
                job, null, Optional.empty());

        verify(scheduleJobService).createJobContext(job);
    }
}
