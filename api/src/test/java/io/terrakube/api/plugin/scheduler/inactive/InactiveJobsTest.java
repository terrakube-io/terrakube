package io.terrakube.api.plugin.scheduler.inactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.data.redis.core.RedisTemplate;

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

    private InactiveJobs subject() {
        return new InactiveJobs(mock(GitLabWebhookService.class), jobRepository, redisTemplate,
                mock(GitHubWebhookService.class), mock(AzDevOpsWebhookService.class), stepRepository, properties,
                jobStatusChangeService);
    }

    @BeforeEach
    void setup() throws Exception {
        doReturn(scheduler).when(context).getScheduler();
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
}
