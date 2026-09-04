package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JobReconciliationSweepTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRepository workspaceRepository;
    Scheduler scheduler;
    ScheduleJobService scheduleJobService;
    RedisTemplate<String, Object> redisTemplate;
    ValueOperations<String, Object> valueOperations;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        stepRepository = mock(StepRepository.class, new FailUnkownMethod<StepRepository>());
        workspaceRepository = mock(WorkspaceRepository.class, new FailUnkownMethod<WorkspaceRepository>());
        scheduler = mock(Scheduler.class, new FailUnkownMethod<Scheduler>());
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());
        redisTemplate = mock(RedisTemplate.class, new FailUnkownMethod<RedisTemplate>());
        valueOperations = mock(ValueOperations.class, new FailUnkownMethod<ValueOperations>());
        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
        lenient().doAnswer(invocation -> invocation.getArgument(0)).when(workspaceRepository).save(any());
        // Default to "Redis has been comfortably up for a while" so existing tests aren't
        // affected by the warm-up check; the tests specifically about it override this.
        lenient().doReturn(uptimeProperties(3600)).when(redisTemplate).execute(any(RedisCallback.class));
    }

    private static Properties uptimeProperties(long seconds) {
        Properties properties = new Properties();
        properties.setProperty("uptime_in_seconds", String.valueOf(seconds));
        return properties;
    }

    private JobReconciliationSweep subject() {
        return new JobReconciliationSweep(jobRepository, stepRepository, workspaceRepository, scheduler, scheduleJobService, redisTemplate);
    }

    private Job job(int id, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setUpdatedDate(new Date(System.currentTimeMillis()));
        job.setWorkspace(new Workspace());
        return job;
    }

    @Test
    void recreatesTheTriggerForAJobWhoseQuartzContextIsMissing() throws Exception {
        Job pending = job(15, JobStatus.pending);
        doReturn(List.of(pending)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(false).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_15"));
        doNothing().when(scheduleJobService).createJobContext(pending);

        subject().execute(null);

        verify(scheduleJobService, times(1)).createJobContext(pending);
    }

    @Test
    void leavesAJobAloneWhenItsTriggerAlreadyExists() throws Exception {
        Job pending = job(16, JobStatus.pending);
        doReturn(List.of(pending)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_16"));

        subject().execute(null);

        verify(scheduleJobService, times(0)).createJobContext(any());
    }

    @Test
    void swallowsAConcurrentRecreationRaceFromAnotherReplica() throws Exception {
        Job pending = job(17, JobStatus.pending);
        doReturn(List.of(pending)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(false).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_17"));
        doThrow(new ObjectAlreadyExistsException("already exists")).when(scheduleJobService).createJobContext(pending);

        subject().execute(null);

        // No exception propagates - the sweep tick completes normally.
    }

    @Test
    void doesNotJudgeAQueueJobYoungerThanTheGracePeriod() throws Exception {
        Job queued = job(20, JobStatus.queue);
        queued.setUpdatedDate(new Date(System.currentTimeMillis() - 5_000)); // 5s old
        doReturn(List.of(queued)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_20"));

        subject().execute(null);

        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(20));
    }

    @Test
    void failsARunningJobPastTheGracePeriodWithNoHeartbeat() throws Exception {
        Job running = job(21, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 350_000)); // 350s old (> 300s grace period)
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_21"));
        doReturn(false).when(redisTemplate).hasKey("executor-job-heartbeat:21");
        doReturn(List.of()).when(stepRepository).findByJobId(21);
        doReturn(1).when(jobRepository).updateStatusById(JobStatus.failed, 21);
        doReturn(true).when(scheduler).deleteJob(new JobKey("TerrakubeV2_Job_21"));

        subject().execute(null);

        verify(jobRepository, times(1)).updateStatusById(JobStatus.failed, 21);
        verify(scheduler, times(1)).deleteJob(new JobKey("TerrakubeV2_Job_21"));
        // updateStatusById is a bulk update that bypasses Elide's JobManageHook, which normally
        // keeps workspace.lastJobStatus in sync - without this explicit save the workspace list
        // UI would keep showing the job's previous status (e.g. "running") forever.
        org.junit.jupiter.api.Assertions.assertEquals(JobStatus.failed, running.getWorkspace().getLastJobStatus());
        verify(workspaceRepository, times(1)).save(running.getWorkspace());
    }

    @Test
    void leavesARunningJobAloneWhenWithinExtendedGracePeriodWithNoHeartbeat() throws Exception {
        // Issue #3521: a cold-start ephemeral pod reaches ~70-90s before writing its first heartbeat.
        // With the 300s default grace period, the sweep must not fail it.
        Job running = job(21, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000)); // 90s old (< 300s)
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_21"));
        doReturn(false).when(redisTemplate).hasKey("executor-job-heartbeat:21");

        subject().execute(null);

        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(21));
    }

    @Test
    void respectsCustomConfiguredHeartbeatGracePeriod() throws Exception {
        JobReconciliationSweep customSweep = new JobReconciliationSweep(
                jobRepository, stepRepository, workspaceRepository, scheduler, scheduleJobService, redisTemplate, 600);

        Job running = job(30, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 450_000)); // 450s old (< 600s)
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_30"));
        doReturn(false).when(redisTemplate).hasKey("executor-job-heartbeat:30");

        customSweep.execute(null);

        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(30));
    }

    @Test
    void leavesARunningJobAloneWhenItsHeartbeatIsStillAlive() throws Exception {
        Job running = job(22, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000));
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_22"));
        doReturn(true).when(redisTemplate).hasKey("executor-job-heartbeat:22");

        subject().execute(null);

        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(22));
    }

    @Test
    void doesNotFailAJobWhenRedisIsUnreachableDuringTheHeartbeatCheck() throws Exception {
        Job running = job(23, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000));
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_23"));
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate).hasKey("executor-job-heartbeat:23");

        subject().execute(null);

        // A Redis outage must never be treated as "the job is dead" - that would fail every
        // in-flight job on a transient blip, which is far worse than a delayed detection.
        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(23));
    }

    @Test
    void doesNotFailAJobWhenRedisWasRecentlyRestarted() throws Exception {
        // This deployment's Redis has no persistent volume - a crash/restart comes back
        // completely empty, so a missing heartbeat right after says nothing about whether the
        // executor is actually still alive. Note hasKey isn't even stubbed here: reaching it
        // would fail this test outright (FailUnkownMethod), proving the warm-up check short-
        // circuits before ever asking Redis about the heartbeat itself.
        Job running = job(25, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000));
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_25"));
        doReturn(uptimeProperties(10)).when(redisTemplate).execute(any(RedisCallback.class));

        subject().execute(null);

        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(25));
    }

    @Test
    void treatsAnUndeterminableRedisUptimeAsRecentlyRestarted() throws Exception {
        Job running = job(26, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000));
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_26"));
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate).execute(any(RedisCallback.class));

        subject().execute(null);

        verify(jobRepository, times(0)).updateStatusById(eq(JobStatus.failed), eq(26));
    }

    @Test
    void doesNotCheckHeartbeatForNonExecutorStatuses() throws Exception {
        Job pending = job(24, JobStatus.pending);
        pending.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000));
        doReturn(List.of(pending)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_24"));

        subject().execute(null);

        verify(redisTemplate, times(0)).hasKey(any());
    }
}
