package io.terrakube.api.plugin.vcs;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * When a new commit is pushed to a pull request, the runs started for its earlier commits are no longer
 * worth running. A workspace runs one job at a time, so they wait in the queue in front of the new run.
 * Opt-in per webhook event ({@link WebhookEvent#isCancelSupersededPrRuns()}).
 *
 * <p>The runs cancelled are the older runs of the same workspace and the same pull request number that have
 * not started yet; see {@link SupersededPullRequestJobTransactions#NOT_STARTED} for why runs in {@code queue}
 * or {@code running} are left alone. The branch name is deliberately not used: it is the head ref name only,
 * so a pull request from a fork (or two pull requests that both use {@code patch-1}) would otherwise cancel
 * runs of unrelated pull requests and push runs on the base branch. The pull request number is only recorded
 * on a run when the PR workflow of the event is enabled, so without it nothing is cancelled. A failure to
 * cancel is logged and never stops the new run.
 */
@Slf4j
@Service
@AllArgsConstructor
public class SupersededPullRequestJobCanceller {

    private final SupersededPullRequestJobTransactions transactions;

    /**
     * Cancels the runs {@code newJob} supersedes when the matched event asks for it and the run was started by
     * a pull request event (not a push, a release or a comment command).
     */
    public void cancelSupersededIfEnabled(WebhookEvent matchedEvent, WebhookResult webhookResult, Job newJob) {
        if (matchedEvent == null || !matchedEvent.isCancelSupersededPrRuns() || webhookResult == null
                || webhookResult.isRelease() || webhookResult.isPrComment()
                || !"pull_request".equals(webhookResult.getNormalizedEvent())) {
            return;
        }
        cancelSupersededPullRequestJobs(newJob);
    }

    /**
     * Cancels the older not started runs of {@code newJob}'s workspace and pull request. Inside a transaction
     * this runs once it has committed, so the new run exists before the old ones are cancelled, and nothing is
     * cancelled when the new run is rolled back.
     */
    public void cancelSupersededPullRequestJobs(Job newJob) {
        if (newJob == null) {
            return;
        }
        Workspace workspace = newJob.getWorkspace();
        Integer prNumber = newJob.getPrNumber();
        int newJobId = newJob.getId();
        if (workspace == null || prNumber == null || prNumber <= 0 || newJobId <= 0) {
            log.info("Job {} has no workspace, pull request number or id, not cancelling superseded runs",
                    newJobId);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cancel(workspace, prNumber, newJobId);
                }
            });
        } else {
            cancel(workspace, prNumber, newJobId);
        }
    }

    private void cancel(Workspace workspace, int prNumber, int newJobId) {
        List<Integer> supersededJobIds;
        try {
            supersededJobIds = transactions.findSupersededJobIds(workspace, prNumber, newJobId);
        } catch (RuntimeException e) {
            log.error("Could not look for runs superseded by job {} on workspace {}", newJobId, workspace.getId(), e);
            return;
        }
        for (Integer jobId : supersededJobIds) {
            try {
                if (transactions.cancelIfNotStarted(jobId)) {
                    log.info("Cancelled job {} on workspace {}: superseded by job {} of pull request {}", jobId,
                            workspace.getId(), newJobId, prNumber);
                }
            } catch (RuntimeException e) {
                log.error("Could not cancel job {} superseded by job {}", jobId, newJobId, e);
            }
        }
    }
}
