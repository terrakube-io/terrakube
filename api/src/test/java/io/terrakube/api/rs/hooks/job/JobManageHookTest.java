package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.subscription.JobStatusEvent;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobManageHookTest {

    @Mock
    ScheduleJobService scheduleJobService;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    JobStatusPublisher jobStatusPublisher;

    @Mock
    AddressRepository addressRepository;

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

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
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

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(jobStatusPublisher).publish(new JobStatusEvent(43, workspaceId.toString(), "pending"), organizationId.toString());
    }

    @Test
    void createsTargetAndReplaceAddressesOnCreate() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(new Organization());

        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);

        Job job = new Job();
        job.setId(44);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.pending);
        job.setTargetAddrs(List.of("aws_instance.foo"));
        job.setReplaceAddrs(List.of("aws_instance.bar", "module.baz.aws_instance.qux"));

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository, times(3)).save(captor.capture());

        List<Address> saved = captor.getAllValues();
        assertEquals(1, saved.stream().filter(a -> a.getType() == AddressType.TARGET && a.getName().equals("aws_instance.foo")).count());
        assertEquals(1, saved.stream().filter(a -> a.getType() == AddressType.REPLACE && a.getName().equals("aws_instance.bar")).count());
        assertEquals(1, saved.stream().filter(a -> a.getType() == AddressType.REPLACE && a.getName().equals("module.baz.aws_instance.qux")).count());
        saved.forEach(a -> assertEquals(job, a.getJob()));
    }

    @Test
    void doesNotCreateAddressesWhenNoneProvidedOnCreate() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(new Organization());

        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);

        Job job = new Job();
        job.setId(45);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.pending);

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(addressRepository, never()).save(any());
    }

    @Test
    void skipsBlankAndNullAddressNames() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(new Organization());

        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);

        Job job = new Job();
        job.setId(46);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.pending);
        job.setTargetAddrs(Arrays.asList(" aws_instance.foo ", "", "   ", null));

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository, times(1)).save(captor.capture());
        assertEquals("aws_instance.foo", captor.getValue().getName());
    }

    @Test
    void schedulesJobEvenWhenAnAddressFailsToSave() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(new Organization());

        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);

        Job job = new Job();
        job.setId(47);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.pending);
        job.setTargetAddrs(List.of("aws_instance.foo", "aws_instance.bar"));

        when(addressRepository.save(any()))
                .thenThrow(new RuntimeException("constraint violation"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        JobManageHook hook = new JobManageHook(scheduleJobService, workspaceRepository, jobStatusPublisher, addressRepository);
        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, job, null, Optional.empty());

        verify(addressRepository, times(2)).save(any());
        verify(scheduleJobService).createJobContext(job);
        verify(jobStatusPublisher).publish(new JobStatusEvent(47, workspaceId.toString(), "pending"), organizationId.toString());
    }
}
