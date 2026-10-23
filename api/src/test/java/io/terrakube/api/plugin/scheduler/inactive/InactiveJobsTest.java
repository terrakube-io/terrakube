package io.terrakube.api.plugin.scheduler.inactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.List;

import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

class InactiveJobsTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final StepRepository stepRepository = mock(StepRepository.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final JobStatusChangeService jobStatusChangeService = mock(JobStatusChangeService.class);
    private final InactiveJobsProperties properties = new InactiveJobsProperties();
    private final Scheduler scheduler = mock(Scheduler.class);
    private final JobExecutionContext context = mock(JobExecutionContext.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
    private final GitHubWebhookService gitHubWebhookService = mock(GitHubWebhookService.class);

    private InactiveJobs subject() {
        return new InactiveJobs(mock(GitLabWebhookService.class), jobRepository, redisTemplate,
                gitHubWebhookService, mock(AzDevOpsWebhookService.class), stepRepository, properties,
                jobStatusChangeService);
    }

    @BeforeEach
    void setup() throws Exception {
        doReturn(scheduler).when(context).getScheduler();
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        doReturn(true).when(valueOperations).setIfAbsent(anyString(), any(), any(Duration.class));
    }

    private Job job(int id, JobStatus status, int createdHoursAgo) {
        Workspace workspace = new Workspace();
        workspace.setLastJobStatus(status);
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setVia(JobVia.UI.getValue());
        job.setWorkspace(workspace);
        job.setOrganization(new Organization());
        job.setCreatedDate(DateUtils.addHours(new Date(), -createdHoursAgo));
        job.setUpdatedDate(DateUtils.addHours(new Date(), -createdHoursAgo));
        return job;
    }

    private void jobs(Job... jobs) {
        doReturn(List.of(jobs)).when(jobRepository).findAllByStatusInOrderByIdAsc(any());
    }

    @Test
    void aRunWaitingForApprovalForSevenHoursIsLeftAlone() throws Exception {
        Job waiting = job(1, JobStatus.waitingApproval, 7);
        jobs(waiting);

        subject().execute(context);

        assertThat(waiting.getStatus()).isEqualTo(JobStatus.waitingApproval);
        verify(jobRepository, never()).save(any());
        verify(jobStatusChangeService, never()).afterStatusChanged(any());
    }

    @Test
    void aStuckRunIsFailedWithAReasonAndEveryFollowUpRuns() throws Exception {
        Job stuck = job(2, JobStatus.running, 7);
        Step pending = new Step();
        pending.setStatus(JobStatus.pending);
        Step done = new Step();
        done.setStatus(JobStatus.completed);
        doReturn(List.of(pending, done)).when(stepRepository).findByJobId(2);
        jobs(stuck);

        subject().execute(context);

        assertThat(stuck.getStatus()).isEqualTo(JobStatus.failed);
        assertThat(stuck.getOutput()).contains("Closed by Terrakube: The run did not finish within 6 hours");
        assertThat(pending.getStatus()).isEqualTo(JobStatus.failed);
        assertThat(done.getStatus()).isEqualTo(JobStatus.completed);
        verify(jobRepository).save(stuck);
        verify(redisTemplate).delete("2");
        verify(jobStatusChangeService).afterStatusChanged(stuck);
        verify(scheduler).deleteJob(new JobKey("TerrakubeV2_Job_2"));
    }

    @Test
    void anApprovalPastTheOptInTimeoutIsCancelledAndItsStepsToo() throws Exception {
        properties.setWaitingApprovalTimeoutHours(48);
        Job waiting = job(3, JobStatus.waitingApproval, 72);
        Step pending = new Step();
        pending.setStatus(JobStatus.pending);
        doReturn(List.of(pending)).when(stepRepository).findByJobId(3);
        jobs(waiting);

        subject().execute(context);

        assertThat(waiting.getStatus()).isEqualTo(JobStatus.cancelled);
        assertThat(waiting.getOutput()).contains("Approval was not given within 48 hours");
        assertThat(pending.getStatus()).isEqualTo(JobStatus.cancelled);
        verify(jobStatusChangeService).afterStatusChanged(waiting);
    }

    @Test
    void aRunInsideTheLimitIsLeftAlone() throws Exception {
        Job fresh = job(4, JobStatus.pending, 2);
        jobs(fresh);

        subject().execute(context);

        assertThat(fresh.getStatus()).isEqualTo(JobStatus.pending);
        verify(jobStatusChangeService, never()).afterStatusChanged(any());
    }

    @Test
    void oneRunThatCannotBeClosedDoesNotStopTheOthers() throws Exception {
        Job broken = job(5, JobStatus.pending, 7);
        Job other = job(6, JobStatus.queue, 7);
        doReturn(List.of()).when(stepRepository).findByJobId(6);
        org.mockito.Mockito.doThrow(new RuntimeException("db")).when(jobRepository).save(broken);
        jobs(broken, other);

        subject().execute(context);

        assertThat(other.getStatus()).isEqualTo(JobStatus.failed);
        verify(jobStatusChangeService).afterStatusChanged(other);
    }

    // A workspace runs one job at a time: a run behind an unfinished one has not started, so it is not stuck.
    @Test
    void aRunWaitingBehindAnUnfinishedRunIsNotClosedHoweverOldItIs() throws Exception {
        Job behind = job(7, JobStatus.queue, 30);
        Job waiting = job(6, JobStatus.waitingApproval, 31);
        doReturn(Optional.of(List.of(waiting))).when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(eq(behind.getWorkspace()), anyList(), eq(7));
        jobs(behind);

        subject().execute(context);

        assertThat(behind.getStatus()).isEqualTo(JobStatus.queue);
        verify(jobRepository, never()).save(any());
        verify(jobStatusChangeService, never()).afterStatusChanged(any());
    }

    @Test
    void theTimeSpentWaitingForTheRunInFrontDoesNotCountAgainstARun() throws Exception {
        Job behind = job(9, JobStatus.pending, 30);
        Job finishedAnHourAgo = job(8, JobStatus.completed, 31);
        finishedAnHourAgo.setUpdatedDate(DateUtils.addHours(new Date(), -1));
        doReturn(Optional.of(List.of())).when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(eq(behind.getWorkspace()), anyList(), eq(9));
        doReturn(Optional.of(finishedAnHourAgo)).when(jobRepository)
                .findFirstByWorkspaceAndIdLessThanOrderByUpdatedDateDesc(behind.getWorkspace(), 9);
        jobs(behind);

        subject().execute(context);

        assertThat(behind.getStatus()).isEqualTo(JobStatus.pending);
        verify(jobStatusChangeService, never()).afterStatusChanged(any());
    }

    @Test
    void aRunThatWasFreeToStartLongAgoIsStillClosed() throws Exception {
        Job stuck = job(11, JobStatus.pending, 30);
        Job finishedLongAgo = job(10, JobStatus.completed, 31);
        finishedLongAgo.setUpdatedDate(DateUtils.addHours(new Date(), -29));
        doReturn(Optional.of(List.of())).when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(eq(stuck.getWorkspace()), anyList(), eq(11));
        doReturn(Optional.of(finishedLongAgo)).when(jobRepository)
                .findFirstByWorkspaceAndIdLessThanOrderByUpdatedDateDesc(stuck.getWorkspace(), 11);
        doReturn(List.of()).when(stepRepository).findByJobId(11);
        jobs(stuck);

        subject().execute(context);

        assertThat(stuck.getStatus()).isEqualTo(JobStatus.failed);
    }

    @Test
    void stepsAreClosedBeforeTheNotificationSoItCanDescribeTheFailedStep() throws Exception {
        Job stuck = job(12, JobStatus.running, 7);
        Step pending = new Step();
        pending.setStatus(JobStatus.pending);
        doReturn(List.of(pending)).when(stepRepository).findByJobId(12);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(pending.getStatus()).isEqualTo(JobStatus.failed);
            return null;
        }).when(jobStatusChangeService).afterStatusChanged(stuck);
        jobs(stuck);

        subject().execute(context);

        verify(jobStatusChangeService).afterStatusChanged(stuck);
    }

    @Test
    void aRunThatAnotherWorkerIsProcessingIsLeftForTheNextPass() throws Exception {
        Job stuck = job(13, JobStatus.running, 7);
        doReturn(false).when(valueOperations).setIfAbsent(eq("job-execution-lock:13"), any(), any(Duration.class));
        jobs(stuck);

        subject().execute(context);

        assertThat(stuck.getStatus()).isEqualTo(JobStatus.running);
        verify(jobRepository, never()).save(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void whenRedisCannotBeReachedNothingIsClosed() throws Exception {
        Job stuck = job(14, JobStatus.running, 7);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down")).when(valueOperations)
                .setIfAbsent(anyString(), any(), any(Duration.class));
        jobs(stuck);

        subject().execute(context);

        assertThat(stuck.getStatus()).isEqualTo(JobStatus.running);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void theLockIsReleasedAfterTheRunIsClosed() throws Exception {
        Job stuck = job(15, JobStatus.running, 7);
        doReturn(List.of()).when(stepRepository).findByJobId(15);
        jobs(stuck);

        subject().execute(context);

        verify(redisTemplate).delete("job-execution-lock:15");
    }

    @Test
    void theVcsCommitStatusIsTheOneOfTheClosingStatus() throws Exception {
        Job stuck = job(16, JobStatus.running, 7);
        stuck.setVia(JobVia.GITHUB.getValue());
        io.terrakube.api.rs.vcs.Vcs vcs = new io.terrakube.api.rs.vcs.Vcs();
        vcs.setVcsType(io.terrakube.api.rs.vcs.VcsType.GITHUB);
        stuck.getWorkspace().setVcs(vcs);
        doReturn(List.of()).when(stepRepository).findByJobId(16);
        jobs(stuck);

        subject().execute(context);

        verify(gitHubWebhookService).sendCommitStatus(stuck, JobStatus.failed, null);
    }
}
