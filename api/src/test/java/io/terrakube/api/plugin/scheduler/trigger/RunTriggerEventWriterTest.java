package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RunTriggerEventWriterTest {

    private static final UUID SOURCE_ID = UUID.randomUUID();

    RunTriggerEventRepository runTriggerEventRepository;
    WorkspaceRunTriggerRepository workspaceRunTriggerRepository;
    RunTriggerProperties properties;
    RunTriggerEventWriter subject;

    @BeforeEach
    void setup() {
        runTriggerEventRepository = mock(RunTriggerEventRepository.class);
        workspaceRunTriggerRepository = mock(WorkspaceRunTriggerRepository.class);
        properties = new RunTriggerProperties();
        lenient().doReturn(true).when(workspaceRunTriggerRepository)
                .existsBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse(SOURCE_ID);
        lenient().doReturn(false).when(runTriggerEventRepository).existsByJob_Id(any(Integer.class));
        subject = new RunTriggerEventWriter(runTriggerEventRepository, workspaceRunTriggerRepository, properties);
    }

    private Job completedJob(int id) {
        Workspace workspace = new Workspace();
        workspace.setId(SOURCE_ID);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        workspace.setOrganization(organization);

        Job job = new Job();
        job.setId(id);
        job.setWorkspace(workspace);
        return job;
    }

    @Test
    void enqueuesAPendingEventWhenTheWorkspaceHasOutboundEdges() {
        Job job = completedJob(900);

        subject.enqueueIfQualifying(job);

        ArgumentCaptor<RunTriggerEvent> captor = ArgumentCaptor.forClass(RunTriggerEvent.class);
        verify(runTriggerEventRepository).save(captor.capture());
        RunTriggerEvent saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getJob()).isEqualTo(job);
        assertThat(saved.getStatus()).isEqualTo(RunTriggerEventStatus.PENDING);
    }

    @Test
    void skipsWhenTheWorkspaceHasNoEnabledOutboundEdges() {
        doReturn(false).when(workspaceRunTriggerRepository)
                .existsBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse(SOURCE_ID);

        subject.enqueueIfQualifying(completedJob(900));

        verify(runTriggerEventRepository, never()).save(any());
    }

    @Test
    void skipsWhenTheFeatureIsDisabled() {
        properties.setEnabled(false);

        subject.enqueueIfQualifying(completedJob(900));

        verify(runTriggerEventRepository, never()).save(any());
        verify(workspaceRunTriggerRepository, never())
                .existsBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse(any());
    }

    @Test
    void skipsAJobWithNoWorkspace() {
        Job job = new Job();
        job.setId(900);

        subject.enqueueIfQualifying(job);

        verify(runTriggerEventRepository, never()).save(any());
    }

    @Test
    void skipsANullJob() {
        subject.enqueueIfQualifying(null);

        verify(runTriggerEventRepository, never()).save(any());
    }

    /** reconcile() should only ever reach this once per job, but the check is defense in depth. */
    @Test
    void skipsWhenAnEventForThisJobAlreadyExists() {
        doReturn(true).when(runTriggerEventRepository).existsByJob_Id(900);

        subject.enqueueIfQualifying(completedJob(900));

        verify(runTriggerEventRepository, never()).save(any());
    }

    /** The database's unique constraint is the real guarantee; this is the race the pre-check can miss. */
    @Test
    void toleratesAConcurrentDuplicateInsertAtTheDatabaseLevel() {
        doThrow(new DataIntegrityViolationException("duplicate key")).when(runTriggerEventRepository).save(any());

        subject.enqueueIfQualifying(completedJob(900));

        verify(runTriggerEventRepository).save(any());
        // No exception propagates - the duplicate is treated as success.
    }
}
