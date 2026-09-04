package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.ReconciliationDisposition;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.StepEvidence;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

/**
 * The single routine that reconciles a non-terminal job with zero pending executable steps to a
 * terminal status derived from its persisted step outcomes. Called by ScheduleJob (inline, when a
 * flow evaluation finds no next step), the 30s reconciliation sweep, and the admin endpoint.
 *
 * <p>Does exactly what the design doc §3.4 requires: derive, row-lock, status transition, workspace
 * last-run status, one notification event, Quartz trigger removal after commit. VCS commit-status,
 * PR comments and job-history pruning are {@code completeJob}-specific extras the scheduler still
 * runs on its own inline path; the sweep path skips them (a stale zombie needs no fresh VCS push).
 *
 * <p>Idempotent and safe under two API replicas: {@link JobRepository#lockForUpdate} serialises
 * concurrent callers on the job row, and an already-terminal job short-circuits with no event.
 */
@Slf4j
@Service
@AllArgsConstructor
public class JobReconciliationService {

    private final JobRepository jobRepository;
    private final StepRepository stepRepository;
    private final WorkspaceRepository workspaceRepository;
    private final JobTerminalStateDeriver deriver;
    private final JobNotificationTrigger jobNotificationTrigger;
    private final ScheduleJobService scheduleJobService;
    private final Scheduler scheduler;
    private final JobReconciliationMetrics metrics;

    @Transactional
    public ReconciliationResult reconcile(int jobId, boolean dryRun) {
        Job job = jobRepository.lockForUpdate(jobId);
        List<Step> steps = stepRepository.findByJobId(jobId);
        List<StepEvidence> evidence = steps.stream()
                .sorted(Comparator.comparingInt(Step::getStepNumber))
                .map(s -> new StepEvidence(s.getStepNumber(), s.getStatus()))
                .toList();

        boolean hasActiveWork = steps.stream().anyMatch(s -> s.getStatus() == JobStatus.pending
                || s.getStatus() == JobStatus.running
                || s.getStatus() == JobStatus.queue);
        if (hasActiveWork) {
            return new ReconciliationResult(jobId, job.getStatus(), null, null,
                    ReconciliationDisposition.SKIPPED_HAS_WORK, evidence);
        }

        DerivedOutcome outcome = deriver.derive(job, steps);
        metrics.observedZeroPendingNonTerminal(job.getStatus());

        switch (outcome) {
            case ALREADY_TERMINAL:
                return result(jobId, job, outcome, null, ReconciliationDisposition.ALREADY_TERMINAL, evidence);
            case RETAIN_WAITING_APPROVAL:
            case ANOMALY:
                metrics.anomaly();
                log.warn("Reconciliation held job {}: status={}, derived={}, steps={}",
                        jobId, job.getStatus(), outcome, evidence);
                return result(jobId, job, outcome, null, ReconciliationDisposition.HELD_ANOMALY, evidence);
            default:
                break; // terminal transition
        }

        JobStatus target = outcome.targetStatus().orElseThrow();
        if (dryRun) {
            return result(jobId, job, outcome, target, ReconciliationDisposition.DRY_RUN, evidence);
        }

        JobStatus from = job.getStatus();
        job.setStatus(target);
        jobRepository.save(job);
        jobNotificationTrigger.notifyStatusChanged(job);
        updateWorkspaceStatus(job);
        metrics.reconciled(target);
        log.info("Reconciled job {} from {} to {} (derived {})", jobId, from, target, outcome);

        deleteTriggerAfterCommit(jobId);
        return result(jobId, job, outcome, target, ReconciliationDisposition.APPLIED, evidence);
    }

    /**
     * Read-only: every non-terminal job with &ge;1 step and zero pending steps, each with its
     * derived target. Backs the admin GET report and the sweep's discovery pass.
     */
    @Transactional(readOnly = true)
    public List<ReconciliationResult> report() {
        return jobRepository.findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES).stream()
                .map(job -> {
                    List<Step> steps = stepRepository.findByJobId(job.getId());
                    boolean hasActiveWork = steps.stream().anyMatch(s -> s.getStatus() == JobStatus.pending
                            || s.getStatus() == JobStatus.running
                            || s.getStatus() == JobStatus.queue);
                    if (steps.isEmpty() || hasActiveWork) {
                        return null;
                    }
                    DerivedOutcome outcome = deriver.derive(job, steps);
                    List<StepEvidence> evidence = steps.stream()
                            .sorted(Comparator.comparingInt(Step::getStepNumber))
                            .map(s -> new StepEvidence(s.getStepNumber(), s.getStatus()))
                            .toList();
                    return new ReconciliationResult(job.getId(), job.getStatus(), outcome,
                            outcome.targetStatus().orElse(null),
                            outcome.isTerminalTransition() ? ReconciliationDisposition.DRY_RUN
                                    : ReconciliationDisposition.HELD_ANOMALY,
                            evidence);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private ReconciliationResult result(int jobId, Job job, DerivedOutcome outcome, JobStatus target,
            ReconciliationDisposition disposition, List<StepEvidence> evidence) {
        return new ReconciliationResult(jobId, job.getStatus(), outcome, target, disposition, evidence);
    }

    // Same 3-line update JobManageHook and JobReconciliationSweep already do independently: keeps
    // workspace.lastJobStatus in sync since a plain jobRepository.save bypasses Elide's hooks.
    private void updateWorkspaceStatus(Job job) {
        Workspace workspace = job.getWorkspace();
        if (workspace == null) {
            return;
        }
        workspace.setLastJobStatus(job.getStatus());
        workspace.setLastJobDate(new Date(System.currentTimeMillis()));
        workspaceRepository.save(workspace);
    }

    private void deleteTriggerAfterCommit(int jobId) {
        Runnable delete = () -> {
            try {
                scheduler.deleteJob(new JobKey(PREFIX_JOB_CONTEXT + jobId));
                Integer next = jobRepository.findNextDispatchableExecutableJobId();
                if (next != null) {
                    scheduleJobService.createJobContextNow(jobRepository.getReferenceById(next));
                }
            } catch (ObjectAlreadyExistsException e) {
                metrics.quartzTriggerRace();
                log.info("Quartz trigger race while reconciling job {}: {}", jobId, e.getMessage());
            } catch (SchedulerException e) {
                metrics.quartzTriggerRace();
                log.warn("Could not remove Quartz trigger for reconciled job {}: {}", jobId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }
}
