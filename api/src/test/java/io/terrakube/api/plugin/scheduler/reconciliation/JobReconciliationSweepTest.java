package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    ReconciliationProperties properties;
    JobReconciliationService reconciliationService;
    JobReconciliationMetrics metrics;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        stepRepository = mock(StepRepository.class, new FailUnkownMethod<StepRepository>());
        workspaceRepository = mock(WorkspaceRepository.class, new FailUnkownMethod<WorkspaceRepository>());
        scheduler = mock(Scheduler.class, new FailUnkownMethod<Scheduler>());
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());
        redisTemplate = mock(RedisTemplate.class, new FailUnkownMethod<RedisTemplate>());
        valueOperations = mock(ValueOperations.class, new FailUnkownMethod<ValueOperations>());
        reconciliationService = mock(JobReconciliationService.class, new FailUnkownMethod<JobReconciliationService>());
        properties = new ReconciliationProperties();
        metrics = new JobReconciliationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
        lenient().doAnswer(invocation -> invocation.getArgument(0)).when(workspaceRepository).save(any());
        // Default: jobs have no steps, so the zero-pending reconciliation pass is a no-op unless a
        // test says otherwise (existing trigger/heartbeat tests are unaffected by this feature).
        lenient().doReturn(List.of()).when(stepRepository).findByJobId(anyInt());
        lenient().doReturn(true).when(valueOperations).setIfAbsent(any(), any(), any(java.time.Duration.class));
        lenient().doReturn(true).when(redisTemplate).delete(anyString());
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
        return new JobReconciliationSweep(jobRepository, stepRepository, workspaceRepository, scheduler,
                scheduleJobService, redisTemplate, properties, reconciliationService, metrics);
    }

    private Job job(int id, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setUpdatedDate(new Date(System.currentTimeMillis()));
        job.setWorkspace(new Workspace());
        return job;
    }

    private Step step(JobStatus status) {
        Step s = new Step();
        s.setStatus(status);
        return s;
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

        verify(jobRepository, times(0)).updateStatusByIdAndStatusIn(eq(JobStatus.failed), eq(20), any());
    }

    @Test
    void failsARunningJobPastTheGracePeriodWithNoHeartbeat() throws Exception {
        Job running = job(21, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000)); // 90s old
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_21"));
        doReturn(false).when(redisTemplate).hasKey("executor-job-heartbeat:21");
        doReturn(List.of()).when(stepRepository).findByJobId(21);
        doReturn(1).when(jobRepository).updateStatusByIdAndStatusIn(JobStatus.failed, 21, List.of(JobStatus.queue, JobStatus.running));
        doReturn(true).when(scheduler).deleteJob(new JobKey("TerrakubeV2_Job_21"));

        subject().execute(null);

        verify(jobRepository, times(1)).updateStatusByIdAndStatusIn(JobStatus.failed, 21, List.of(JobStatus.queue, JobStatus.running));
        verify(scheduler, times(1)).deleteJob(new JobKey("TerrakubeV2_Job_21"));
        // updateStatusById is a bulk update that bypasses Elide's JobManageHook, which normally
        // keeps workspace.lastJobStatus in sync - without this explicit save the workspace list
        // UI would keep showing the job's previous status (e.g. "running") forever.
        org.junit.jupiter.api.Assertions.assertEquals(JobStatus.failed, running.getWorkspace().getLastJobStatus());
        verify(workspaceRepository, times(1)).save(running.getWorkspace());
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

        verify(jobRepository, times(0)).updateStatusByIdAndStatusIn(eq(JobStatus.failed), eq(22), any());
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
        verify(jobRepository, times(0)).updateStatusByIdAndStatusIn(eq(JobStatus.failed), eq(23), any());
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

        verify(jobRepository, times(0)).updateStatusByIdAndStatusIn(eq(JobStatus.failed), eq(25), any());
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

        verify(jobRepository, times(0)).updateStatusByIdAndStatusIn(eq(JobStatus.failed), eq(26), any());
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

    @Test
    void reconcilesAZombieApprovedJobBeforeReconcilingItsTrigger() throws Exception {
        Job zombie = job(30, JobStatus.approved);
        doReturn(List.of(zombie)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(List.of(step(JobStatus.completed))).when(stepRepository).findByJobId(30);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_30"));
        doReturn(new ReconciliationResult(30, JobStatus.approved, DerivedOutcome.COMPLETED,
                JobStatus.completed, ReconciliationResult.ReconciliationDisposition.APPLIED, List.of()))
                .when(reconciliationService).reconcile(30, false); // autoRemediate default true

        subject().execute(null);

        verify(reconciliationService, times(1)).reconcile(30, false);
    }

    @Test
    void runsReconcileInDryRunWhenAutoRemediateIsOff() throws Exception {
        properties.setAutoRemediate(false);
        Job zombie = job(31, JobStatus.approved);
        doReturn(List.of(zombie)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(List.of(step(JobStatus.completed))).when(stepRepository).findByJobId(31);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_31"));
        doReturn(new ReconciliationResult(31, JobStatus.approved, DerivedOutcome.COMPLETED,
                JobStatus.completed, ReconciliationResult.ReconciliationDisposition.DRY_RUN, List.of()))
                .when(reconciliationService).reconcile(31, true);

        subject().execute(null);

        verify(reconciliationService, times(1)).reconcile(31, true);
    }

    @Test
    void doesNotReconcileAJobThatStillHasAPendingStep() throws Exception {
        Job working = job(32, JobStatus.approved);
        doReturn(List.of(working)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(List.of(step(JobStatus.pending))).when(stepRepository).findByJobId(32);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_32"));

        subject().execute(null);

        verify(reconciliationService, never()).reconcile(anyInt(), anyBoolean());
    }

    @Test
    void doesNotReconcileWhenSweepDisabled() throws Exception {
        properties.setSweepEnabled(false);
        Job zombie = job(33, JobStatus.approved);
        doReturn(List.of(zombie)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_33"));

        subject().execute(null);

        verify(reconciliationService, never()).reconcile(anyInt(), anyBoolean());
    }

    @Test
    void sweepDoesNotRecreateTriggerAfterReconcilingToTerminal() throws Exception {
        Job zombie = job(40, JobStatus.approved);
        doReturn(List.of(zombie)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(List.of(step(JobStatus.completed))).when(stepRepository).findByJobId(40);
        doReturn(false).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_40"));
        doReturn(new ReconciliationResult(40, JobStatus.approved, DerivedOutcome.COMPLETED,
                JobStatus.completed, ReconciliationResult.ReconciliationDisposition.APPLIED, List.of()))
                .when(reconciliationService).reconcile(40, false);

        subject().execute(null);

        verify(reconciliationService, times(1)).reconcile(40, false);
        verify(scheduleJobService, never()).createJobContext(any());
    }

    @Test
    void sweepDoesNotFlagInFlightJobOnFinalStepAsAnomaly() throws Exception {
        Job running = job(41, JobStatus.running);
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        // Step 1 completed, Step 2 running (in flight)
        doReturn(List.of(step(JobStatus.completed), step(JobStatus.running)))
                .when(stepRepository).findByJobId(41);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_41"));
        doReturn(true).when(redisTemplate).hasKey("executor-job-heartbeat:41");

        subject().execute(null);

        // Active work in progress: must never call reconcile
        verify(reconciliationService, never()).reconcile(anyInt(), anyBoolean());
    }

    @Test
    void sweepDoesNotOverwriteJobWhenItIsNoLongerInExecutorOwnedStatus() throws Exception {
        Job running = job(42, JobStatus.running);
        running.setUpdatedDate(new Date(System.currentTimeMillis() - 90_000));
        doReturn(List.of(running)).when(jobRepository)
                .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
        doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_42"));
        doReturn(false).when(redisTemplate).hasKey("executor-job-heartbeat:42");
        // Simulated race: by the time heartbeat check executes, job was completed in DB (rows updated = 0)
        doReturn(0).when(jobRepository).updateStatusByIdAndStatusIn(
                JobStatus.failed, 42, List.of(JobStatus.queue, JobStatus.running));

        subject().execute(null);

        verify(jobRepository, times(1)).updateStatusByIdAndStatusIn(
                JobStatus.failed, 42, List.of(JobStatus.queue, JobStatus.running));
        verify(stepRepository, never()).save(any());
        verify(workspaceRepository, never()).save(any());
        verify(scheduler, never()).deleteJob(any());
    }
}
