package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.plugin.scheduler.spool.SpoolRecoveryService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.rs.job.step.DeliveryState;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

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
@Component
public class JobReconciliationSweep implements org.quartz.Job {

    static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.pending, JobStatus.approved, JobStatus.waitingApproval,
            JobStatus.queue, JobStatus.running);

    // Duplicated as a literal in executor's JobExecutionWatchdog - separate Spring Boot apps, no
    // shared module for this constant.
    private static final String HEARTBEAT_PREFIX = "executor-job-heartbeat:";

    // Redis here has no persistent volume, so a restart comes back completely empty - every
    // heartbeat gone at once. Give executors a full refresh cycle (15s) plus margin to
    // re-populate before trusting a missing key means the executor is actually dead.
    private static final Duration REDIS_WARMUP_PERIOD = Duration.ofSeconds(90);

    // Workspaces corrected per sweep, so a large backlog is worked off over a few sweeps.
    private static final int STALE_WORKSPACE_BATCH = 100;

    // Same key ScheduleJob's withExecutionLock uses, so a sweep-driven reconcile and a live
    // ScheduleJob firing for the same job id never run concurrently across replicas.
    private static final String EXECUTION_LOCK_PREFIX = "job-execution-lock:";
    private static final Duration EXECUTION_LOCK_TTL = Duration.ofSeconds(90);

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRepository workspaceRepository;
    Scheduler scheduler;
    ScheduleJobService scheduleJobService;
    RedisTemplate<String, Object> redisTemplate;
    ReconciliationProperties properties;
    JobReconciliationService reconciliationService;
    JobReconciliationMetrics metrics;
    JobStatusChangeService jobStatusChangeService;
    Duration heartbeatGracePeriod;
    StorageTypeService storageTypeService;
    SpoolRecoveryService spoolRecoveryService;

    @Autowired
    public JobReconciliationSweep(
            JobRepository jobRepository,
            StepRepository stepRepository,
            WorkspaceRepository workspaceRepository,
            Scheduler scheduler,
            ScheduleJobService scheduleJobService,
            RedisTemplate<String, Object> redisTemplate,
            ReconciliationProperties properties,
            JobReconciliationService reconciliationService,
            JobReconciliationMetrics metrics,
            JobStatusChangeService jobStatusChangeService,
            @Value("${io.terrakube.scheduler.reconciliation.heartbeat-grace-period-seconds:${ReconciliationHeartbeatGracePeriodSeconds:300}}") long heartbeatGracePeriodSeconds,
            StorageTypeService storageTypeService,
            SpoolRecoveryService spoolRecoveryService) {
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.workspaceRepository = workspaceRepository;
        this.scheduler = scheduler;
        this.scheduleJobService = scheduleJobService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.reconciliationService = reconciliationService;
        this.metrics = metrics;
        this.jobStatusChangeService = jobStatusChangeService;
        this.heartbeatGracePeriod = Duration.ofSeconds(heartbeatGracePeriodSeconds);
        this.storageTypeService = storageTypeService;
        this.spoolRecoveryService = spoolRecoveryService;
    }

    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        boolean redisRecentlyRestarted = isRedisWithinWarmupPeriod();
        for (Job job : jobRepository.findAllByStatusInOrderByIdAsc(ACTIVE_STATUSES)) {
            if (properties.isSweepEnabled()) {
                ReconciliationResult result = reconcileZeroPendingJob(job);
                if (result != null) {
                    if (result.disposition() == ReconciliationResult.ReconciliationDisposition.APPLIED && result.targetStatus() != null) {
                        job.setStatus(result.targetStatus());
                    }
                    if (result.disposition() == ReconciliationResult.ReconciliationDisposition.APPLIED
                            || result.disposition() == ReconciliationResult.ReconciliationDisposition.ALREADY_TERMINAL) {
                        continue;
                    }
                }
            }
            reconcileTrigger(job);
            if (isExecutorOwnedStatus(job.getStatus())) {
                failIfExecutorHeartbeatExpired(job, redisRecentlyRestarted);
            }
        }
        if (properties.isSweepEnabled()) {
            repairStaleWorkspaceStatus();
        }
    }

    // A workspace whose last job status says "waiting approval", "running" etc. although none of its
    // runs is active has been left behind by a status change made without Elide's hooks (#3727).
    // Correct it from the workspace's newest run. Only that stale-active shape is repaired: the
    // status otherwise legitimately follows whichever run changed last, so comparing it with the
    // newest run in general would flip it back and forth.
    private void repairStaleWorkspaceStatus() {
        List<Workspace> stale = workspaceRepository.findWithStaleActiveJobStatus(
                ACTIVE_STATUSES, PageRequest.of(0, STALE_WORKSPACE_BATCH));
        for (Workspace workspace : stale) {
            try {
                Optional<Job> newest = jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace);
                JobStatus actual = newest.map(Job::getStatus).orElse(JobStatus.NeverExecuted);
                JobStatus previous = workspace.getLastJobStatus();
                if (actual == previous || ACTIVE_STATUSES.contains(actual)) {
                    continue;
                }
                log.warn("Workspace {} shows {} but has no active run, setting it to {}",
                        workspace.getId(), previous, actual);
                if (!properties.isAutoRemediate()) {
                    continue;
                }
                // A workspace that never ran has no date; otherwise it is the date of the run it shows.
                Date date = newest.map(job -> job.getUpdatedDate() != null ? job.getUpdatedDate() : job.getCreatedDate())
                        .orElse(null);
                // One conditional update, not a save of the workspace read a moment ago: a run created in
                // between has set its own status, and a concurrent edit of the workspace is not overwritten.
                int repaired = workspaceRepository.repairStaleJobStatus(
                        workspace.getId(), previous, actual, date, ACTIVE_STATUSES);
                if (repaired == 0) {
                    log.info("Workspace {} changed while it was being repaired, leaving it", workspace.getId());
                    continue;
                }
                metrics.workspaceStatusRepaired(previous);
                // Open pages learn of the correction without a reload.
                newest.ifPresent(jobStatusChangeService::publishStatus);
            } catch (RuntimeException e) {
                log.warn("Could not repair the status of workspace {}, will retry next sweep",
                        workspace.getId(), e);
            }
        }
    }

    // Hands a job that is non-terminal with >=1 step and no active work (pending, running, or queue)
    // to the shared reconciliation routine (dry-run when auto-remediate is off). Jobs with no steps yet keep
    // today's trigger-only reconciliation - they're just uninitialised, not stuck.
    private ReconciliationResult reconcileZeroPendingJob(Job job) {
        if (job.getWorkspace() == null) {
            return null;
        }
        List<Step> steps = stepRepository.findByJobId(job.getId());
        boolean hasActiveWork = steps.stream().anyMatch(s -> s.getStatus() == JobStatus.pending
                || s.getStatus() == JobStatus.running
                || s.getStatus() == JobStatus.queue);
        if (steps.isEmpty() || hasActiveWork) {
            return null;
        }
        if (!acquirePerJobLock(job.getId())) {
            return null; // a live ScheduleJob firing (or another replica's sweep) owns this job now
        }
        try {
            return reconciliationService.reconcile(job.getId(), !properties.isAutoRemediate());
        } catch (RuntimeException e) {
            log.warn("Reconciliation of zero-pending job {} failed this sweep, will retry: {}",
                    job.getId(), e.getMessage());
            return null;
        } finally {
            releasePerJobLock(job.getId());
        }
    }

    // Fails closed like ScheduleJob.acquireExecutionLock: an unreachable Redis means "skip this
    // job this sweep" rather than reconciling without the cross-replica guard.
    private boolean acquirePerJobLock(int jobId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(EXECUTION_LOCK_PREFIX + jobId, "1", EXECUTION_LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException e) {
            log.warn("Could not reach Redis for the reconciliation lock on job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    private void releasePerJobLock(int jobId) {
        try {
            redisTemplate.delete(EXECUTION_LOCK_PREFIX + jobId);
        } catch (DataAccessException e) {
            log.warn("Could not release the reconciliation lock on job {} (TTL will clear it): {}", jobId, e.getMessage());
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
        if (!ACTIVE_STATUSES.contains(job.getStatus())) {
            return;
        }
        if (job.getWorkspace() == null) {
            log.warn("Job {} has no active workspace, cancelling orphaned job", job.getId());
            try {
                job.setStatus(JobStatus.cancelled);
                jobRepository.save(job);
                for (Step step : stepRepository.findByJobId(job.getId())) {
                    if (step.getStatus() == JobStatus.pending || step.getStatus() == JobStatus.running || step.getStatus() == JobStatus.queue) {
                        step.setStatus(JobStatus.cancelled);
                        stepRepository.save(step);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to cancel orphaned job {} during sweep: {}", job.getId(), e.getMessage(), e);
            }
            return;
        }
        try {
            JobKey key = new JobKey(PREFIX_JOB_CONTEXT + job.getId());
            if (!scheduler.checkExists(key)) {
                log.warn("Job {} has no Quartz trigger despite status {}, recreating it", job.getId(), job.getStatus());
                scheduleJobService.createJobContext(job);
                metrics.quartzTriggerRecreated();
            }
        } catch (ObjectAlreadyExistsException e) {
            metrics.quartzTriggerRace();
            log.info("Job {}'s trigger was recreated by another cluster member first, ignoring", job.getId());
        } catch (ParseException | SchedulerException e) {
            log.warn("Could not reconcile trigger for Job {}, will retry next sweep: {}", job.getId(), e.getMessage());
        }
    }

    // Fails a job whose executor heartbeat has expired. Skips jobs younger than
    // heartbeatGracePeriod so a freshly-dispatched job gets a full refresh cycle first. Unlike
    // most Redis errors elsewhere in this codebase, an unreachable Redis here does nothing rather
    // than assuming the job is dead - presuming a live job dead risks failing it mid-apply.
    private void failIfExecutorHeartbeatExpired(Job job, boolean redisRecentlyRestarted) {
        if (job.getUpdatedDate() == null) {
            return;
        }
        Duration age = Duration.between(job.getUpdatedDate().toInstant(), Instant.now());
        if (age.compareTo(heartbeatGracePeriod) < 0) {
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
        int rowsUpdated = jobRepository.updateStatusByIdAndStatusIn(
                JobStatus.failed, job.getId(), List.of(JobStatus.queue, JobStatus.running));
        if (rowsUpdated == 0) {
            log.info("Job {} is no longer in queue or running status in DB, skipping heartbeat failure", job.getId());
            return;
        }
        job.setStatus(JobStatus.failed);
        for (Step step : stepRepository.findByJobId(job.getId())) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(JobStatus.failed);
                stepRepository.save(step);
            }
        }
        // updateStatusByIdAndStatusIn is a bulk JPQL update that bypasses Elide's hooks, so do what
        // they would have done: keep workspace.lastJobStatus in sync, publish the live status event
        // and send the notifications.
        jobStatusChangeService.afterStatusChanged(job);
        try {
            scheduler.deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
        } catch (SchedulerException e) {
            log.warn("Could not delete Quartz context for failed Job {}: {}", job.getId(), e.getMessage());
        }
        // After failing a dead executor's job, probe storage for any spooled final output
        // that could not be delivered before the executor died. This runs inside the sweep's
        // open transaction, but is rare (only on dead executors whose heartbeat expired).
        probeAndRecoverSpooledSteps(job);
    }

    private void probeAndRecoverSpooledSteps(Job job) {
        if (storageTypeService == null || job.getOrganization() == null) {
            return;
        }
        try {
            String orgId = job.getOrganization().getId().toString();
            String jobId = String.valueOf(job.getId());
            for (Step step : stepRepository.findByJobId(job.getId())) {
                if (step.getId() == null) {
                    continue;
                }
                DeliveryState state = step.getDeliveryState();
                if (state == DeliveryState.ACKED || state == DeliveryState.RECOVERED || state == DeliveryState.EXPIRED) {
                    continue;
                }
                String stepId = step.getId().toString();
                for (String phase : List.of("plan", "apply")) {
                    if (storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase)) {
                        log.info("Dead executor probe found spooled context patch for step {} job {} phase {}", stepId, jobId, phase);
                        if (step.getDeliveryState() != DeliveryState.SPOOLED) {
                            step.setDeliveryState(DeliveryState.SPOOLED);
                            stepRepository.save(step);
                        }
                        Date now = new Date();
                        Date leaseUntil = new Date(now.getTime() + 60_000L);
                        String owner = "reconciliation-probe-" + UUID.randomUUID();
                        int leased = stepRepository.acquireLease(step.getId(), owner, leaseUntil, now);
                        if (leased > 0 && spoolRecoveryService != null) {
                            step.setLeaseOwner(owner);
                            spoolRecoveryService.recoverStep(step);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error probing spooled output for dead executor of job {}: {}", job.getId(), e.getMessage());
        }
    }
}
