package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Derives the terminal status a job should hold when it has zero pending executable steps,
 * from the persisted step outcomes alone. Pure: no repository or scheduler access.
 *
 * <p>Precedence (see design doc 2026-09-02 &sect;3.3):
 * <ol>
 *   <li>job already terminal &rarr; ALREADY_TERMINAL;</li>
 *   <li>any failed step &rarr; FAILED;</li>
 *   <li>any cancelled step &rarr; CANCELLED;</li>
 *   <li>any rejected step, or the job itself rejected &rarr; REJECTED;</li>
 *   <li>job waitingApproval &rarr; RETAIN_WAITING_APPROVAL (no transition);</li>
 *   <li>&ge;1 step and every step done-ok &rarr; COMPLETED (covers the no-change plan);</li>
 *   <li>otherwise &rarr; ANOMALY (no transition, operator-visible).</li>
 * </ol>
 *
 * <p>The caller guarantees {@code steps} has no {@link JobStatus#pending} entry before calling.
 * Flow config is not needed: {@code initJobConfiguration} creates exactly one step per flow
 * entry up front, so "all steps done" is equivalent to "all flow steps done".
 */
@Slf4j
@Component
public class JobTerminalStateDeriver {

    private static final Set<JobStatus> TERMINAL_JOB_STATUSES = Set.of(
            JobStatus.completed, JobStatus.failed, JobStatus.rejected,
            JobStatus.cancelled, JobStatus.noChanges);

    // Step statuses that mean "this step is done and did not fail".
    private static final Set<JobStatus> STEP_DONE_OK = Set.of(JobStatus.completed, JobStatus.notExecuted);

    public DerivedOutcome derive(Job job, List<Step> steps) {
        if (TERMINAL_JOB_STATUSES.contains(job.getStatus())) {
            return DerivedOutcome.ALREADY_TERMINAL;
        }
        if (steps.stream().anyMatch(s -> s.getStatus() == JobStatus.failed)) {
            return DerivedOutcome.FAILED;
        }
        if (steps.stream().anyMatch(s -> s.getStatus() == JobStatus.cancelled)) {
            return DerivedOutcome.CANCELLED;
        }
        if (job.getStatus() == JobStatus.rejected
                || steps.stream().anyMatch(s -> s.getStatus() == JobStatus.rejected)) {
            return DerivedOutcome.REJECTED;
        }
        if (job.getStatus() == JobStatus.waitingApproval) {
            return DerivedOutcome.RETAIN_WAITING_APPROVAL;
        }
        if (!steps.isEmpty() && steps.stream().allMatch(s -> STEP_DONE_OK.contains(s.getStatus()))) {
            return DerivedOutcome.COMPLETED;
        }
        log.warn("Job {} has zero pending steps but no derivable terminal state: status={}, steps={}",
                job.getId(), job.getStatus(),
                steps.stream().map(Step::getStatus).toList());
        return DerivedOutcome.ANOMALY;
    }
}
