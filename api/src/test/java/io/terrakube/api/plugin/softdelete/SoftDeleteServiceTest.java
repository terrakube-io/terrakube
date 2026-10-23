package io.terrakube.api.plugin.softdelete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

class SoftDeleteServiceTest {

    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobStatusChangeService jobStatusChangeService = mock(JobStatusChangeService.class);
    private final SoftDeleteService subject = new SoftDeleteService(scheduleJobService, null, null, null, null,
            jobRepository, null, jobStatusChangeService);

    private Workspace workspace() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        return workspace;
    }

    @Test
    void cancellingTheJobsOfADeletedWorkspaceRunsTheStatusChangeFollowUp() throws Exception {
        Workspace workspace = workspace();
        Job job = new Job();
        job.setId(9);
        job.setStatus(JobStatus.waitingApproval);
        doReturn(List.of(9)).when(jobRepository).findActiveJobIdsByWorkspace(workspace.getId().toString());
        doReturn(Optional.of(job)).when(jobRepository).findById(9);

        subject.disableWorkspaceJobs(workspace);

        verify(scheduleJobService).deleteJobContext(9);
        verify(jobRepository).updateStatusById(JobStatus.cancelled, 9);
        assertThat(job.getStatus()).isEqualTo(JobStatus.cancelled);
        verify(jobStatusChangeService).afterStatusChanged(job);
    }

    @Test
    void aJobThatFailsToCancelDoesNotStopTheNext() throws Exception {
        Workspace workspace = workspace();
        Job second = new Job();
        second.setId(11);
        doReturn(List.of(10, 11)).when(jobRepository).findActiveJobIdsByWorkspace(workspace.getId().toString());
        org.mockito.Mockito.doThrow(new RuntimeException("quartz")).when(scheduleJobService).deleteJobContext(10);
        doReturn(Optional.of(second)).when(jobRepository).findById(11);

        subject.disableWorkspaceJobs(workspace);

        verify(jobRepository, never()).updateStatusById(JobStatus.cancelled, 10);
        verify(jobRepository).updateStatusById(JobStatus.cancelled, 11);
        verify(jobStatusChangeService).afterStatusChanged(second);
    }
}
