package io.terrakube.api.plugin.workspace;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceJobStatusServiceTest {

    @Mock
    JobRepository jobRepository;
    @Mock
    WorkspaceRepository workspaceRepository;

    WorkspaceJobStatusService service;
    Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new WorkspaceJobStatusService(jobRepository, workspaceRepository);
        workspace = new Workspace();
    }

    private Job job(int id, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setWorkspace(workspace);
        return job;
    }

    private void olderActiveJob(Job other, Job changed) {
        when(jobRepository.findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(eq(workspace), eq(changed.getId()), any()))
                .thenReturn(Optional.of(other));
    }

    @Test
    void aNewPendingRunBehindARunWaitingForApprovalKeepsTheWaitingStatus() {
        Job waiting = job(1, JobStatus.waitingApproval);
        Job created = job(2, JobStatus.pending);
        olderActiveJob(waiting, created);

        assertEquals(JobStatus.waitingApproval, service.statusFor(created));
    }

    @Test
    void theOldestUnfinishedRunDecidesNotTheOneThatChangedLast() {
        Job running = job(1, JobStatus.running);
        Job queued = job(2, JobStatus.queue);
        olderActiveJob(running, queued);

        assertEquals(JobStatus.running, service.statusFor(queued));
    }

    @Test
    void theOldestUnfinishedRunShowsItsOwnNewStatusWhenItIsTheOneThatChanged() {
        Job waiting = job(1, JobStatus.approved);
        Job newer = job(2, JobStatus.pending);
        when(jobRepository.findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(eq(workspace), eq(1), any()))
                .thenReturn(Optional.of(newer));

        assertEquals(JobStatus.approved, service.statusFor(waiting));
    }

    @Test
    void aFinishedRunShowsTheStatusOfTheRunThatNowHoldsTheQueue() {
        Job finished = job(1, JobStatus.completed);
        Job next = job(2, JobStatus.pending);
        olderActiveJob(next, finished);

        assertEquals(JobStatus.pending, service.statusFor(finished));
    }

    @Test
    void whenNothingElseIsUnfinishedTheChangedRunDecides() {
        Job failed = job(3, JobStatus.failed);
        when(jobRepository.findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(any(), anyInt(), any()))
                .thenReturn(Optional.empty());

        assertEquals(JobStatus.failed, service.statusFor(failed));
    }

    @Test
    void refreshSavesTheStatusAndTheDateOnTheWorkspace() {
        Job waiting = job(1, JobStatus.waitingApproval);
        Job created = job(2, JobStatus.pending);
        olderActiveJob(waiting, created);

        service.refresh(created);

        assertEquals(JobStatus.waitingApproval, workspace.getLastJobStatus());
        assertNotNull(workspace.getLastJobDate());
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void aJobWithoutWorkspaceChangesNothing() {
        Job orphan = new Job();
        orphan.setId(9);
        orphan.setStatus(JobStatus.running);

        service.refresh(orphan);

        assertEquals(JobStatus.running, service.statusFor(orphan));
        verify(workspaceRepository, never()).save(any());
    }
}
