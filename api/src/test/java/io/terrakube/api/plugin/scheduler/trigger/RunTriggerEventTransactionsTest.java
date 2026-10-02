package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RunTriggerEventTransactionsTest {

    RunTriggerEventRepository runTriggerEventRepository;
    RunTriggerEventTransactions subject;

    @BeforeEach
    void setup() {
        runTriggerEventRepository = mock(RunTriggerEventRepository.class);
        subject = new RunTriggerEventTransactions(runTriggerEventRepository);
    }

    @Test
    void claimReturnsNullWhenTheRowWasNotClaimable() {
        UUID id = UUID.randomUUID();
        doReturn(0).when(runTriggerEventRepository).claimForProcessing(eq(id), eq(RunTriggerEventStatus.PENDING),
                eq(RunTriggerEventStatus.PROCESSING), any());

        assertThat(subject.claim(id)).isNull();
        verify(runTriggerEventRepository, never()).findById(any());
    }

    @Test
    void claimReturnsTheClaimedRowsDataWhenSuccessful() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        Job job = new Job();
        job.setId(755);
        RunTriggerEvent event = new RunTriggerEvent();
        event.setId(id);
        event.setJob(job);
        event.setAttemptCount(2);
        event.setLastAttemptAt(lastAttemptAt);
        doReturn(1).when(runTriggerEventRepository).claimForProcessing(eq(id), eq(RunTriggerEventStatus.PENDING),
                eq(RunTriggerEventStatus.PROCESSING), any());
        doReturn(Optional.of(event)).when(runTriggerEventRepository).findById(id);

        ClaimedEvent claimed = subject.claim(id);

        assertThat(claimed).isNotNull();
        assertThat(claimed.jobId()).isEqualTo(755);
        assertThat(claimed.attemptCount()).isEqualTo(2);
        assertThat(claimed.lastAttemptAt()).isEqualTo(lastAttemptAt);
    }

    /** Claimed the row but it vanished before the follow-up read - treated the same as not claimed. */
    @Test
    void claimReturnsNullWhenTheRowDisappearsBetweenClaimAndRead() {
        UUID id = UUID.randomUUID();
        doReturn(1).when(runTriggerEventRepository).claimForProcessing(eq(id), eq(RunTriggerEventStatus.PENDING),
                eq(RunTriggerEventStatus.PROCESSING), any());
        doReturn(Optional.empty()).when(runTriggerEventRepository).findById(id);

        assertThat(subject.claim(id)).isNull();
    }

    @Test
    void recordResultDelegatesToTheConditionalUpdate() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        Date nextAttemptAt = new Date(lastAttemptAt.getTime() + 60_000);

        subject.recordResult(id, lastAttemptAt, RunTriggerEventStatus.PENDING, "boom", nextAttemptAt);

        verify(runTriggerEventRepository).recordResult(eq(id), eq(RunTriggerEventStatus.PROCESSING),
                eq(lastAttemptAt), eq(RunTriggerEventStatus.PENDING), eq("boom"), eq(nextAttemptAt), any());
    }

    @Test
    void recordResultAcceptsANullErrorAndNextAttemptOnSuccess() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();

        subject.recordResult(id, lastAttemptAt, RunTriggerEventStatus.PROCESSED, null, null);

        verify(runTriggerEventRepository).recordResult(eq(id), eq(RunTriggerEventStatus.PROCESSING),
                eq(lastAttemptAt), eq(RunTriggerEventStatus.PROCESSED), isNull(), isNull(), any());
    }

    @Test
    void sweepStuckProcessingRowsReclaimsAndFailsInOneCall() {
        Date cutoff = new Date();

        subject.sweepStuckProcessingRows(cutoff, 3);

        verify(runTriggerEventRepository).reclaimStuckProcessingRows(eq(RunTriggerEventStatus.PROCESSING),
                eq(RunTriggerEventStatus.PENDING), eq(cutoff), eq(3), any());
        verify(runTriggerEventRepository).failStuckProcessingRowsAtMaxAttempts(eq(RunTriggerEventStatus.PROCESSING),
                eq(RunTriggerEventStatus.FAILED), eq(cutoff), eq(3), any(), any());
    }

    @Test
    void rearmForRetryReturnsTrueOnlyWhenARowWasActuallyRearmed() {
        UUID id = UUID.randomUUID();
        doReturn(1).when(runTriggerEventRepository).rearmFailedForRetry(eq(id), eq(RunTriggerEventStatus.FAILED),
                eq(RunTriggerEventStatus.PENDING), any());

        assertThat(subject.rearmForRetry(id)).isTrue();
    }

    @Test
    void rearmForRetryReturnsFalseWhenTheRowWasNotFailed() {
        UUID id = UUID.randomUUID();
        doReturn(0).when(runTriggerEventRepository).rearmFailedForRetry(eq(id), eq(RunTriggerEventStatus.FAILED),
                eq(RunTriggerEventStatus.PENDING), any());

        assertThat(subject.rearmForRetry(id)).isFalse();
    }

    @Test
    void pruneTerminalRowsOlderThanDelegatesToTheDeleteQuery() {
        Date cutoff = new Date();
        doReturn(5).when(runTriggerEventRepository).deleteTerminalRowsCreatedBefore(
                eq(List.of(RunTriggerEventStatus.PROCESSED, RunTriggerEventStatus.FAILED)), eq(cutoff));

        int deleted = subject.pruneTerminalRowsOlderThan(cutoff);

        assertThat(deleted).isEqualTo(5);
    }
}
