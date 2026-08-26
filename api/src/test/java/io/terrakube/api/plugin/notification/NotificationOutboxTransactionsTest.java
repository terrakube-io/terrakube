package io.terrakube.api.plugin.notification;

import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxTransactionsTest {

    @Mock
    NotificationOutboxRepository notificationOutboxRepository;

    @InjectMocks
    NotificationOutboxTransactions subject;

    @Test
    void claimReturnsNullWhenTheRowWasNotClaimable() {
        UUID id = UUID.randomUUID();
        when(notificationOutboxRepository.claimForDelivery(eq(id), eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.SENDING), any())).thenReturn(0);

        assertThat(subject.claim(id)).isNull();
        verify(notificationOutboxRepository, never()).findById(any());
    }

    @Test
    void claimReturnsTheClaimedRowsDataWhenSuccessful() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        NotificationOutbox row = new NotificationOutbox();
        row.setId(id);
        row.setPayload("{}");
        row.setAttemptCount(1);
        row.setLastAttemptAt(lastAttemptAt);
        when(notificationOutboxRepository.claimForDelivery(eq(id), eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.SENDING), any())).thenReturn(1);
        when(notificationOutboxRepository.findById(id)).thenReturn(Optional.of(row));

        ClaimedOutbox claimed = subject.claim(id);

        assertThat(claimed).isNotNull();
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.lastAttemptAt()).isEqualTo(lastAttemptAt);
        assertThat(claimed.payload()).isEqualTo("{}");
    }

    @Test
    void recordResultDelegatesToTheConditionalUpdate() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        Date nextAttemptAt = new Date(lastAttemptAt.getTime() + 60_000);
        when(notificationOutboxRepository.recordDeliveryResult(eq(id), eq(NotificationOutboxStatus.SENDING),
                eq(lastAttemptAt), eq(NotificationOutboxStatus.PENDING), eq("boom"), eq(nextAttemptAt), any()))
                .thenReturn(1);

        subject.recordResult(id, lastAttemptAt, NotificationOutboxStatus.PENDING, "boom", nextAttemptAt);

        verify(notificationOutboxRepository).recordDeliveryResult(eq(id), eq(NotificationOutboxStatus.SENDING),
                eq(lastAttemptAt), eq(NotificationOutboxStatus.PENDING), eq("boom"), eq(nextAttemptAt), any());
    }

    @Test
    void sweepStuckSendingRowsReclaimsAndFailsInOneTransaction() {
        Date cutoff = new Date();

        subject.sweepStuckSendingRows(cutoff, 3);

        verify(notificationOutboxRepository).reclaimStuckSendingRows(eq(NotificationOutboxStatus.SENDING),
                eq(NotificationOutboxStatus.PENDING), eq(cutoff), eq(3), any());
        verify(notificationOutboxRepository).failStuckSendingRowsAtMaxAttempts(eq(NotificationOutboxStatus.SENDING),
                eq(NotificationOutboxStatus.FAILED), eq(cutoff), eq(3), any(), any());
    }

    @Test
    void pruneTerminalRowsOlderThanDelegatesToTheDeleteQuery() {
        Date cutoff = new Date();
        when(notificationOutboxRepository.deleteTerminalRowsCreatedBefore(
                eq(java.util.List.of(NotificationOutboxStatus.SENT, NotificationOutboxStatus.FAILED)), eq(cutoff)))
                .thenReturn(5);

        int deleted = subject.pruneTerminalRowsOlderThan(cutoff);

        assertThat(deleted).isEqualTo(5);
        verify(notificationOutboxRepository).deleteTerminalRowsCreatedBefore(
                eq(java.util.List.of(NotificationOutboxStatus.SENT, NotificationOutboxStatus.FAILED)), eq(cutoff));
    }
}
