package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

/**
 * Runs every 30 seconds to repair two ways a job can get stuck:
 *
 * 1. Its Quartz trigger was lost (e.g. a crash between Quartz committing a trigger delete and
 *    Spring committing the job's status change - separate transactions) - recreate it.
 * 2. It's queue/running and the executor that had it is gone (heartbeat expired) - fail it so
 *    FIFO isn't blocked on a callback that will never arrive.
 */
@Slf4j
@AllArgsConstructor
@Component
public class JobReconciliationSweep implements org.quartz.Job {

    static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.pending, JobStatus.approved, JobStatus.waitingApproval,
            JobStatus.queue, JobStatus.running);

    // Duplicated as a literal in executor's JobExecutionWatchdog - separate Spring Boot apps, no
    // shared module for this constant.
    private static final String HEARTBEAT_PREFIX = "executor-job-heartbeat:";
    private static final Duration HEARTBEAT_GRACE_PERIOD = Duration.ofSeconds(60);

    // Redis here has no persistent volume, so a restart comes back completely empty - every
    // heartbeat gone at once. Give executors a full refresh cycle (15s) plus margin to
    // re-populate before trusting a missing key means the executor is actually dead.
    private static final Duration REDIS_WARMUP_PERIOD = Duration.ofSeconds(90);

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRepository workspaceRepository;
    Scheduler scheduler;
    ScheduleJobService scheduleJobService;
    RedisTemplate<String, Object> redisTemplate;

    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        boolean redisRecentlyRestarted = isRedisWithinWarmupPeriod();
        for (Job job : jobRepository.findAllByStatusInOrderByIdAsc(ACTIVE_STATUSES)) {
            reconcileTrigger(job);
            if (isExecutorOwnedStatus(job.getStatus())) {
                failIfExecutorHeartbeatExpired(job, redisRecentlyRestarted);
            }
        }
    }

    // Can't determine uptime (Redis unreachable, malformed response) -> treat as recently
    // restarted. Same fail-open philosophy as the heartbeat check below.
    private boolean isRedisWithinWarmupPeriod() {
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));
            if (info == null) {
                return true;
            }
            String uptimeSeconds = info.getProperty("uptime_in_seconds");
            if (uptimeSeconds == null) {
                return true;
            }
            return Long.parseLong(uptimeSeconds) < REDIS_WARMUP_PERIOD.getSeconds();
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("Could not determine Redis uptime, treating it as recently restarted this sweep: {}", e.getMessage());
            return true;
        }
    }

    private boolean isExecutorOwnedStatus(JobStatus status) {
        return status == JobStatus.queue || status == JobStatus.running;
    }

    private void reconcileTrigger(Job job) {
        try {
            JobKey key = new JobKey(PREFIX_JOB_CONTEXT + job.getId());
            if (!scheduler.checkExists(key)) {
                log.warn("Job {} has no Quartz trigger despite status {}, recreating it", job.getId(), job.getStatus());
                scheduleJobService.createJobContext(job);
            }
        } catch (ObjectAlreadyExistsException e) {
            log.info("Job {}'s trigger was recreated by another cluster member first, ignoring", job.getId());
        } catch (ParseException | SchedulerException e) {
            log.warn("Could not reconcile trigger for Job {}, will retry next sweep: {}", job.getId(), e.getMessage());
        }
    }

    // Fails a job whose executor heartbeat has expired. Skips jobs younger than
    // HEARTBEAT_GRACE_PERIOD so a freshly-dispatched job gets a full refresh cycle first. Unlike
    // most Redis errors elsewhere in this codebase, an unreachable Redis here does nothing rather
    // than assuming the job is dead - presuming a live job dead risks failing it mid-apply.
    private void failIfExecutorHeartbeatExpired(Job job, boolean redisRecentlyRestarted) {
        if (job.getUpdatedDate() == null) {
            return;
        }
        Duration age = Duration.between(job.getUpdatedDate().toInstant(), Instant.now());
        if (age.compareTo(HEARTBEAT_GRACE_PERIOD) < 0) {
            return;
        }
        if (redisRecentlyRestarted) {
            log.info("Redis was recently restarted, skipping heartbeat judgment for Job {} this sweep", job.getId());
            return;
        }

        boolean alive;
        try {
            alive = Boolean.TRUE.equals(redisTemplate.hasKey(HEARTBEAT_PREFIX + job.getId()));
        } catch (DataAccessException e) {
            log.warn("Could not check executor heartbeat for Job {}, leaving it as-is this sweep: {}", job.getId(), e.getMessage());
            return;
        }
        if (alive) {
            return;
        }

        log.warn("Job {} has no executor heartbeat after {}, the executor that had it is gone - failing it", job.getId(), age);
        jobRepository.updateStatusById(JobStatus.failed, job.getId());
        for (Step step : stepRepository.findByJobId(job.getId())) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(JobStatus.failed);
                stepRepository.save(step);
            }
        }
        // updateStatusById is a bulk JPQL update - it bypasses Elide's JobManageHook (which
        // normally keeps workspace.lastJobStatus in sync on every entity-managed job update), so
        // without this the workspace list UI would keep showing the job's previous status
        // (typically "running") forever after this sweep fails it.
        Workspace workspace = job.getWorkspace();
        if (workspace != null) {
            workspace.setLastJobStatus(JobStatus.failed);
            workspace.setLastJobDate(new Date(System.currentTimeMillis()));
            workspaceRepository.save(workspace);
        }
        try {
            scheduler.deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
        } catch (SchedulerException e) {
            log.warn("Could not delete Quartz context for failed Job {}: {}", job.getId(), e.getMessage());
        }
    }
}
