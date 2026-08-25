package io.terrakube.api.plugin.vcs;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoWebhookDeliveryTransactionsTest {

    @Mock
    RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;

    RepoWebhookDeliveryTransactions subject;

    @BeforeEach
    void setUp() {
        subject = new RepoWebhookDeliveryTransactions(repoWebhookDeliveryRepository);
    }

    @Test
    void enqueueSavesAPendingRowAndReturnsItsId() {
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setId(UUID.randomUUID());

        UUID id = subject.enqueue(repoWebhook, "{\"a\":1}", "{\"x-github-event\":\"push\"}");

        assertThat(id).isNotNull();
        verify(repoWebhookDeliveryRepository).save(argThat(delivery ->
                delivery.getId().equals(id)
                        && delivery.getRepoWebhook() == repoWebhook
                        && delivery.getPayload().equals("{\"a\":1}")
                        && delivery.getHeaders().equals("{\"x-github-event\":\"push\"}")
                        && delivery.getStatus() == RepoWebhookDeliveryStatus.PENDING));
    }

    @Test
    void claimReturnsNullWhenTheRowWasNotClaimable() {
        UUID id = UUID.randomUUID();
        when(repoWebhookDeliveryRepository.claimForDelivery(eq(id), eq(RepoWebhookDeliveryStatus.PENDING),
                eq(RepoWebhookDeliveryStatus.PROCESSING), any())).thenReturn(0);

        assertThat(subject.claim(id)).isNull();
        verify(repoWebhookDeliveryRepository, never()).findById(any());
    }

    @Test
    void claimReturnsTheClaimedRowsDataWhenSuccessful() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setId(UUID.randomUUID());
        RepoWebhookDelivery row = new RepoWebhookDelivery();
        row.setId(id);
        row.setRepoWebhook(repoWebhook);
        row.setPayload("{}");
        row.setHeaders("{}");
        row.setAttemptCount(1);
        row.setLastAttemptAt(lastAttemptAt);
        when(repoWebhookDeliveryRepository.claimForDelivery(eq(id), eq(RepoWebhookDeliveryStatus.PENDING),
                eq(RepoWebhookDeliveryStatus.PROCESSING), any())).thenReturn(1);
        when(repoWebhookDeliveryRepository.findById(id)).thenReturn(Optional.of(row));

        ClaimedDelivery claimed = subject.claim(id);

        assertThat(claimed).isNotNull();
        assertThat(claimed.repoWebhook()).isSameAs(repoWebhook);
        assertThat(claimed.payload()).isEqualTo("{}");
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.lastAttemptAt()).isEqualTo(lastAttemptAt);
    }

    @Test
    void recordResultDelegatesToTheConditionalUpdate() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        Date nextAttemptAt = new Date(lastAttemptAt.getTime() + 60_000);

        subject.recordResult(id, lastAttemptAt, RepoWebhookDeliveryStatus.PENDING, "boom", nextAttemptAt);

        verify(repoWebhookDeliveryRepository).recordDeliveryResult(eq(id), eq(RepoWebhookDeliveryStatus.PROCESSING),
                eq(lastAttemptAt), eq(RepoWebhookDeliveryStatus.PENDING), eq("boom"), eq(nextAttemptAt), any());
    }

    @Test
    void sweepStuckProcessingRowsReclaimsAndFailsInOneCall() {
        Date cutoff = new Date();

        subject.sweepStuckProcessingRows(cutoff, 3);

        verify(repoWebhookDeliveryRepository).reclaimStuckProcessingRows(eq(RepoWebhookDeliveryStatus.PROCESSING),
                eq(RepoWebhookDeliveryStatus.PENDING), eq(cutoff), eq(3), any());
        verify(repoWebhookDeliveryRepository).failStuckProcessingRowsAtMaxAttempts(eq(RepoWebhookDeliveryStatus.PROCESSING),
                eq(RepoWebhookDeliveryStatus.FAILED), eq(cutoff), eq(3), any(), any());
    }

    @Test
    void pruneTerminalRowsOlderThanDelegatesToTheDeleteQuery() {
        Date cutoff = new Date();
        when(repoWebhookDeliveryRepository.deleteTerminalRowsCreatedBefore(
                eq(java.util.List.of(RepoWebhookDeliveryStatus.PROCESSED, RepoWebhookDeliveryStatus.FAILED)), eq(cutoff)))
                .thenReturn(5);

        int deleted = subject.pruneTerminalRowsOlderThan(cutoff);

        assertThat(deleted).isEqualTo(5);
    }
}
