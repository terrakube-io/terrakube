package io.terrakube.api.plugin.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.SchedulerException;

import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

class SupersededPullRequestJobTransactionsTest {

    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;
    JobStatusChangeService jobStatusChangeService;
    SupersededPullRequestJobTransactions subject;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class);
        scheduleJobService = mock(ScheduleJobService.class);
        jobStatusChangeService = mock(JobStatusChangeService.class);
        subject = new SupersededPullRequestJobTransactions(jobRepository, scheduleJobService, jobStatusChangeService);
    }

    @Test
    void onlyRunsThatHaveNotStartedAreSuperseded() {
        assertEquals(List.of(JobStatus.pending, JobStatus.waitingApproval, JobStatus.approved),
                SupersededPullRequestJobTransactions.NOT_STARTED);
    }

    @Test
    void looksForOlderNotStartedRunsOfTheSameWorkspaceAndPullRequest() {
        Workspace workspace = new Workspace();
        Job older = new Job();
        older.setId(7);
        doReturn(List.of(older)).when(jobRepository)
                .findByWorkspaceAndPrNumberAndIdLessThanAndStatusInOrderByIdAsc(any(), any(), anyInt(), any());

        List<Integer> ids = subject.findSupersededJobIds(workspace, 12, 50);

        assertEquals(List.of(7), ids);
        verify(jobRepository).findByWorkspaceAndPrNumberAndIdLessThanAndStatusInOrderByIdAsc(workspace, 12, 50,
                SupersededPullRequestJobTransactions.NOT_STARTED);
    }

    @Test
    void cancelsANotStartedRunTheWayTheUiDoes() throws Exception {
        Job job = new Job();
        job.setId(7);
        job.setStatus(JobStatus.pending);
        doReturn(1).when(jobRepository).updateStatusByIdAndStatusIn(JobStatus.cancelled, 7,
                SupersededPullRequestJobTransactions.NOT_STARTED);
        doReturn(Optional.of(job)).when(jobRepository).findById(7);

        assertTrue(subject.cancelIfNotStarted(7));

        verify(scheduleJobService).deleteJobContext(7);
        ArgumentCaptor<Job> changed = ArgumentCaptor.forClass(Job.class);
        verify(jobStatusChangeService).afterStatusChanged(changed.capture());
        assertEquals(JobStatus.cancelled, changed.getValue().getStatus());
    }

    @Test
    void aRunThatStartedInTheMeantimeIsLeftAlone() throws Exception {
        doReturn(0).when(jobRepository).updateStatusByIdAndStatusIn(eq(JobStatus.cancelled), eq(7), anyList());

        assertFalse(subject.cancelIfNotStarted(7));

        verify(scheduleJobService, never()).deleteJobContext(anyInt());
        verify(jobStatusChangeService, never()).afterStatusChanged(any());
    }

    @Test
    void theCancelStandsWhenTheQuartzContextCannotBeRemoved() throws Exception {
        Job job = new Job();
        job.setId(7);
        doReturn(1).when(jobRepository).updateStatusByIdAndStatusIn(eq(JobStatus.cancelled), eq(7), anyList());
        doReturn(Optional.of(job)).when(jobRepository).findById(7);
        doThrow(new SchedulerException("quartz down")).when(scheduleJobService).deleteJobContext(7);

        assertTrue(subject.cancelIfNotStarted(7));

        verify(jobStatusChangeService).afterStatusChanged(job);
    }
}
