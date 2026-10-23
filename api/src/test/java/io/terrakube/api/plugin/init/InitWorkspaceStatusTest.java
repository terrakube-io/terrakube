package io.terrakube.api.plugin.init;

import io.terrakube.api.plugin.workspace.WorkspaceJobStatusService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InitWorkspaceStatusTest {

    @Mock
    OrganizationRepository organizationRepository;
    @Mock
    WorkspaceRepository workspaceRepository;
    @Mock
    JobRepository jobRepository;
    @Mock
    WorkspaceJobStatusService workspaceJobStatusService;

    InitWorkspaceStatus init;
    Organization organization = new Organization();
    Workspace workspace = new Workspace();

    @BeforeEach
    void setUp() {
        init = new InitWorkspaceStatus(organizationRepository, workspaceRepository, jobRepository, workspaceJobStatusService);
        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        when(workspaceRepository.findWorkspacesByOrganization(organization)).thenReturn(Optional.of(List.of(workspace)));
    }

    private Job lastJob() {
        Job job = new Job();
        job.setId(5);
        job.setStatus(JobStatus.pending);
        when(jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void aWorkspaceThatShowsTheWrongStatusIsFixed() {
        Job job = lastJob();
        Date date = new Date(1_000_000_000L);
        workspace.setLastJobStatus(JobStatus.pending);
        when(workspaceJobStatusService.shownFor(job))
                .thenReturn(new WorkspaceJobStatusService.Shown(JobStatus.waitingApproval, date));

        init.initWorkspaceStatus();

        assertEquals(JobStatus.waitingApproval, workspace.getLastJobStatus());
        assertEquals(date, workspace.getLastJobDate());
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void aWorkspaceThatIsAlreadyRightIsNotWritten() {
        Job job = lastJob();
        Date date = new Date(1_000_000_000L);
        workspace.setLastJobStatus(JobStatus.waitingApproval);
        workspace.setLastJobDate(date);
        when(workspaceJobStatusService.shownFor(job))
                .thenReturn(new WorkspaceJobStatusService.Shown(JobStatus.waitingApproval, new Date(date.getTime())));

        init.initWorkspaceStatus();

        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void aWorkspaceWithoutJobsThatShowsAStatusIsMarkedNeverExecuted() {
        workspace.setLastJobStatus(JobStatus.completed);
        when(jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace)).thenReturn(Optional.empty());

        init.initWorkspaceStatus();
        assertEquals(JobStatus.NeverExecuted, workspace.getLastJobStatus());
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void aWorkspaceWithoutJobsThatIsAlreadyMarkedIsNotWritten() {
        workspace.setLastJobStatus(JobStatus.NeverExecuted);
        when(jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace)).thenReturn(Optional.empty());

        init.initWorkspaceStatus();

        verify(workspaceRepository, never()).save(any());
    }
}
