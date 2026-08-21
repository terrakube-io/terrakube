package io.terrakube.api.plugin.notification;

import io.terrakube.api.plugin.notification.sender.NotificationDeliveryException;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryService;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock
    NotificationOutboxTransactions notificationOutboxTransactions;
    @Mock
    NotificationDeliveryService notificationDeliveryService;

    @InjectMocks
    NotificationDispatchService subject;

    private ClaimedOutbox claimed(int attemptCount, Date lastAttemptAt) {
        return new ClaimedOutbox(null, "{}", attemptCount, lastAttemptAt);
    }

    @Test
    void successMarksRowSentAndClearsLastError() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        when(notificationOutboxTransactions.claim(id)).thenReturn(claimed(1, lastAttemptAt));
        doNothing().when(notificationDeliveryService).deliver(any(), any());

        subject.attemptDelivery(id);

        verify(notificationOutboxTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(NotificationOutboxStatus.SENT), isNull(), isNull());
    }

    @Test
    void failureBelowThreeAttemptsStaysPendingWithBackoff() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        when(notificationOutboxTransactions.claim(id)).thenReturn(claimed(1, lastAttemptAt));
        doThrow(new NotificationDeliveryException("boom")).when(notificationDeliveryService).deliver(any(), any());

        subject.attemptDelivery(id);

        ArgumentCaptor<Date> nextAttemptAtCaptor = ArgumentCaptor.forClass(Date.class);
        verify(notificationOutboxTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(NotificationOutboxStatus.PENDING), eq("boom"), nextAttemptAtCaptor.capture());
        assertThat(nextAttemptAtCaptor.getValue()).isAfter(new Date());
    }

    @Test
    void thirdFailedAttemptMarksRowPermanentlyFailed() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        when(notificationOutboxTransactions.claim(id)).thenReturn(claimed(3, lastAttemptAt));
        doThrow(new NotificationDeliveryException("still broken")).when(notificationDeliveryService).deliver(any(),
                any());

        subject.attemptDelivery(id);

        verify(notificationOutboxTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(NotificationOutboxStatus.FAILED), eq("still broken"), isNull());
    }

    @Test
    void nonRetryableFailureIsMarkedFailedImmediatelyEvenOnFirstAttempt() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        when(notificationOutboxTransactions.claim(id)).thenReturn(claimed(1, lastAttemptAt));
        doThrow(new NotificationDeliveryException("Webhook endpoint returned status 404", null, false))
                .when(notificationDeliveryService).deliver(any(), any());

        subject.attemptDelivery(id);

        verify(notificationOutboxTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(NotificationOutboxStatus.FAILED), eq("Webhook endpoint returned status 404"), isNull());
    }

    @Test
    void retryAfterHintOverridesTheDefaultBackoff() {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        when(notificationOutboxTransactions.claim(id)).thenReturn(claimed(1, lastAttemptAt));
        doThrow(new NotificationDeliveryException("rate limited", null, true, Duration.ofMinutes(10)))
                .when(notificationDeliveryService).deliver(any(), any());

        subject.attemptDelivery(id);

        ArgumentCaptor<Date> nextAttemptAtCaptor = ArgumentCaptor.forClass(Date.class);
        verify(notificationOutboxTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(NotificationOutboxStatus.PENDING), eq("rate limited"), nextAttemptAtCaptor.capture());
        long delayMillis = nextAttemptAtCaptor.getValue().getTime() - System.currentTimeMillis();
        assertThat(delayMillis).isGreaterThan(Duration.ofMinutes(9).toMillis());
    }

    @Test
    void unclaimedRowIsSkippedWithoutAttemptingDelivery() {
        UUID id = UUID.randomUUID();
        when(notificationOutboxTransactions.claim(id)).thenReturn(null);

        subject.attemptDelivery(id);

        verifyNoInteractions(notificationDeliveryService);
        verify(notificationOutboxTransactions, never()).recordResult(any(), any(), any(), any(), any());
    }
}
