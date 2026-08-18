package io.terrakube.api.plugin.scheduler.notification;

import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.plugin.notification.NotificationOutboxTransactions;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxPollerJobTest {

    @Mock
    NotificationOutboxRepository notificationOutboxRepository;
    @Mock
    NotificationDispatchService notificationDispatchService;
    @Mock
    NotificationOutboxTransactions notificationOutboxTransactions;
    @Mock
    JobExecutionContext jobExecutionContext;

    NotificationOutboxPollerJob subject;

    @BeforeEach
    void setUp() {
        // Constructed directly rather than via @InjectMocks: the constructor now takes plain
        // int/long tuning values (batchSize, stuckSendingThresholdMinutes) that @Value only
        // resolves in a real Spring context - @InjectMocks would silently default them to 0,
        // which breaks PageRequest.of(0, 0).
        subject = new NotificationOutboxPollerJob(notificationOutboxRepository, notificationDispatchService,
                notificationOutboxTransactions, 200, 5);
    }

    private NotificationOutbox row() {
        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        return outbox;
    }

    @Test
    void dispatchesEveryRowReturnedAsDue() throws Exception {
        NotificationOutbox first = row();
        NotificationOutbox second = row();
        when(notificationOutboxRepository.findDueForDispatch(eq(NotificationOutboxStatus.PENDING), any(),
                any(Pageable.class))).thenReturn(List.of(first, second));

        subject.execute(jobExecutionContext);

        verify(notificationDispatchService).dispatchAsync(first.getId());
        verify(notificationDispatchService).dispatchAsync(second.getId());
    }

    @Test
    void sweepsStuckSendingRowsEveryTickViaTheTransactionalBean() throws Exception {
        when(notificationOutboxRepository.findDueForDispatch(any(), any(), any(Pageable.class))).thenReturn(List.of());

        subject.execute(jobExecutionContext);

        verify(notificationOutboxTransactions).sweepStuckSendingRows(any(), eq(3));
    }

    @Test
    void aRejectedDispatchDoesNotStopTheRestOfTheBatch() throws Exception {
        NotificationOutbox first = row();
        NotificationOutbox second = row();
        when(notificationOutboxRepository.findDueForDispatch(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        doThrow(new RejectedExecutionException("pool full")).when(notificationDispatchService)
                .dispatchAsync(first.getId());

        subject.execute(jobExecutionContext);

        verify(notificationDispatchService).dispatchAsync(second.getId());
    }
}
