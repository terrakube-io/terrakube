package io.terrakube.api.plugin.vcs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

class SupersededPullRequestJobCancellerTest {

    SupersededPullRequestJobTransactions transactions;
    SupersededPullRequestJobCanceller subject;

    Workspace workspace;
    WebhookEvent event;
    WebhookResult pullRequest;
    Job newJob;

    @BeforeEach
    void setup() {
        transactions = mock(SupersededPullRequestJobTransactions.class);
        subject = new SupersededPullRequestJobCanceller(transactions);

        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());

        event = new WebhookEvent();
        event.setEvent(WebhookEventType.PULL_REQUEST);
        event.setCancelSupersededPrRuns(true);

        pullRequest = new WebhookResult();
        pullRequest.setEvent("pull_request");
        pullRequest.setBranch("feature/login");
        pullRequest.setPrNumber(12);

        newJob = new Job();
        newJob.setId(50);
        newJob.setWorkspace(workspace);
        newJob.setOverrideBranch("feature/login");
        newJob.setPrNumber(12);
    }

    @Test
    void flagOffCancelsNothing() {
        event.setCancelSupersededPrRuns(false);

        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void noMatchedEventCancelsNothing() {
        subject.cancelSupersededIfEnabled(null, pullRequest, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void pushEventCancelsNothingEvenWithTheFlagOn() {
        WebhookResult push = new WebhookResult();
        push.setEvent("push");
        push.setBranch("feature/login");

        subject.cancelSupersededIfEnabled(event, push, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void commentCommandCancelsNothingEvenWithTheFlagOn() {
        WebhookResult comment = new WebhookResult();
        comment.setEvent("issue_comment");
        comment.setPrComment(true);
        comment.setPrNumber(12);

        subject.cancelSupersededIfEnabled(event, comment, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void releaseCancelsNothingEvenWithTheFlagOn() {
        WebhookResult release = new WebhookResult();
        release.setEvent("release");
        release.setRelease(true);

        subject.cancelSupersededIfEnabled(event, release, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void flagOnCancelsEveryOlderNotStartedRunOfTheSameWorkspaceAndPullRequest() {
        doReturn(List.of(10, 20, 30)).when(transactions).findSupersededJobIds(workspace, 12, 50);
        doReturn(true).when(transactions).cancelIfNotStarted(anyInt());

        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        verify(transactions).findSupersededJobIds(workspace, 12, 50);
        verify(transactions).cancelIfNotStarted(10);
        verify(transactions).cancelIfNotStarted(20);
        verify(transactions).cancelIfNotStarted(30);
        verify(transactions, never()).cancelIfNotStarted(50);
    }

    @Test
    void mergeRequestEventIsAPullRequestEvent() {
        pullRequest.setEvent("merge_request");
        doReturn(List.of(10)).when(transactions).findSupersededJobIds(workspace, 12, 50);

        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        verify(transactions).cancelIfNotStarted(10);
    }

    @Test
    void runWithoutPullRequestNumberCancelsNothingEvenWithTheSameBranch() {
        // The PR workflow is off, so the run carries no pull request number. The branch name alone cannot
        // tell a pull request from a fork or from another pull request, so nothing may be cancelled.
        newJob.setPrNumber(null);

        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void zeroPullRequestNumberCancelsNothing() {
        newJob.setPrNumber(0);

        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        verifyNoInteractions(transactions);
    }

    @Test
    void theBranchNameDoesNotDecideWhichRunsAreCancelled() {
        // Two pull requests that use the same head branch name (a fork's "main", "patch-1") are different
        // pull requests: only the number of the new run is looked up.
        newJob.setOverrideBranch("main");
        doReturn(List.of(10)).when(transactions).findSupersededJobIds(workspace, 12, 50);

        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        verify(transactions).findSupersededJobIds(workspace, 12, 50);
        verify(transactions).cancelIfNotStarted(10);
    }

    @Test
    void jobWithoutWorkspaceOrIdCancelsNothing() {
        Job unsaved = new Job();
        unsaved.setWorkspace(workspace);
        unsaved.setPrNumber(12);
        subject.cancelSupersededIfEnabled(event, pullRequest, unsaved);

        newJob.setWorkspace(null);
        subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

        subject.cancelSupersededIfEnabled(event, pullRequest, null);

        verifyNoInteractions(transactions);
    }

    @Test
    void oneFailingCancelDoesNotStopTheOthers() {
        doReturn(List.of(10, 20, 30)).when(transactions).findSupersededJobIds(workspace, 12, 50);
        doThrow(new RuntimeException("boom")).when(transactions).cancelIfNotStarted(20);

        assertDoesNotThrow(() -> subject.cancelSupersededIfEnabled(event, pullRequest, newJob));

        verify(transactions).cancelIfNotStarted(10);
        verify(transactions).cancelIfNotStarted(20);
        verify(transactions).cancelIfNotStarted(30);
    }

    @Test
    void aFailedLookupIsLoggedAndNotThrown() {
        doThrow(new RuntimeException("db down")).when(transactions).findSupersededJobIds(any(), anyInt(), anyInt());

        assertDoesNotThrow(() -> subject.cancelSupersededIfEnabled(event, pullRequest, newJob));

        verify(transactions, never()).cancelIfNotStarted(anyInt());
    }

    @Test
    void insideATransactionTheRunsAreCancelledOnlyAfterItCommits() {
        doReturn(List.of(10)).when(transactions).findSupersededJobIds(workspace, 12, 50);
        TransactionSynchronizationManager.initSynchronization();
        try {
            subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

            verifyNoInteractions(transactions);

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(transactions).cancelIfNotStarted(10);
    }

    @Test
    void aRolledBackTransactionCancelsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            subject.cancelSupersededIfEnabled(event, pullRequest, newJob);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verifyNoInteractions(transactions);
    }
}
