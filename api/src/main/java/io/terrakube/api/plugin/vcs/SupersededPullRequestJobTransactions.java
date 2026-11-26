package io.terrakube.api.plugin.vcs;

import java.text.ParseException;
import java.util.List;

import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The database work of {@link SupersededPullRequestJobCanceller}. A separate bean so each call goes through
 * a proxy and runs in its own transaction: one run that cannot be cancelled must not undo the others, and
 * nothing here may mark the transaction that created the new run as rollback-only.
 */
@Slf4j
@Service
@AllArgsConstructor
class SupersededPullRequestJobTransactions {

    /**
     * Only runs that have not started are cancelled. A run in {@code queue} or {@code running} has been (or is
     * being) handed to an executor: removing its Quartz context would not stop Terraform, and the status
     * written here would race with the executor's own updates.
     */
    static final List<JobStatus> NOT_STARTED = List.of(JobStatus.pending, JobStatus.waitingApproval,
            JobStatus.approved);

    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;
    private final JobStatusChangeService jobStatusChangeService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Integer> findSupersededJobIds(Workspace workspace, int prNumber, int newJobId) {
        return jobRepository
                .findByWorkspaceAndPrNumberAndIdLessThanAndStatusInOrderByIdAsc(workspace, prNumber, newJobId,
                        NOT_STARTED)
                .stream()
                .map(Job::getId)
                .toList();
    }

    /**
     * Cancels the run the same way a cancel from the UI does (status {@code cancelled}, then
     * {@link ScheduleJobService#deleteJobContext}), and returns whether it was cancelled. The status is only
     * changed while the run is still not started, in one statement, so a run the scheduler hands to an
     * executor in the meantime is left alone (and the scheduler's own dispatch check then sees the cancel).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelIfNotStarted(int jobId) {
        if (jobRepository.updateStatusByIdAndStatusIn(JobStatus.cancelled, jobId, NOT_STARTED) == 0) {
            log.info("Job {} is no longer waiting to start, not cancelling it", jobId);
            return false;
        }
        try {
            scheduleJobService.deleteJobContext(jobId);
        } catch (ParseException | SchedulerException e) {
            // The job is cancelled already: its trigger, if it fires again, sees the status and removes itself.
            log.warn("Could not remove the Quartz context of cancelled job {}: {}", jobId, e.getMessage());
        }
        // The bulk update above bypasses Elide's hooks: do what they would have done for a cancel.
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getApprovedBy() != null) {
                log.info("Job {} had been approved by {}; its approval was for a commit that is now superseded",
                        jobId, job.getApprovedBy());
            }
            job.setStatus(JobStatus.cancelled);
            jobStatusChangeService.afterStatusChanged(job);
        });
        return true;
    }
}
