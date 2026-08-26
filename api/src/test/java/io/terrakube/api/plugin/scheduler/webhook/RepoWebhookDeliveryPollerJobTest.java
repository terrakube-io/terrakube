package io.terrakube.api.plugin.scheduler.webhook;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.springframework.data.domain.Pageable;

import io.terrakube.api.plugin.vcs.RepoWebhookDeliveryTransactions;
import io.terrakube.api.plugin.vcs.RepoWebhookDispatchService;
import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoWebhookDeliveryPollerJobTest {

    @Mock
    RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;
    @Mock
    RepoWebhookDispatchService repoWebhookDispatchService;
    @Mock
    RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;
    @Mock
    JobExecutionContext jobExecutionContext;

    RepoWebhookDeliveryPollerJob subject;

    @BeforeEach
    void setUp() {
        // Constructed directly rather than via @InjectMocks: the constructor takes plain int/long
        // tuning values (batchSize, stuckProcessingThresholdMinutes) that @Value only resolves in
        // a real Spring context - @InjectMocks would silently default them to 0.
        subject = new RepoWebhookDeliveryPollerJob(repoWebhookDeliveryRepository, repoWebhookDispatchService,
                repoWebhookDeliveryTransactions, 200, 5);
    }

    private RepoWebhookDelivery row() {
        RepoWebhookDelivery delivery = new RepoWebhookDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setStatus(RepoWebhookDeliveryStatus.PENDING);
        return delivery;
    }

    @Test
    void dispatchesEveryRowReturnedAsDue() throws Exception {
        RepoWebhookDelivery first = row();
        RepoWebhookDelivery second = row();
        when(repoWebhookDeliveryRepository.findDueForDispatch(eq(RepoWebhookDeliveryStatus.PENDING), any(),
                any(Pageable.class))).thenReturn(List.of(first, second));

        subject.execute(jobExecutionContext);

        verify(repoWebhookDispatchService).dispatchAsync(first.getId());
        verify(repoWebhookDispatchService).dispatchAsync(second.getId());
    }

    @Test
    void sweepsStuckProcessingRowsEveryTickViaTheTransactionalBean() throws Exception {
        when(repoWebhookDeliveryRepository.findDueForDispatch(any(), any(), any(Pageable.class))).thenReturn(List.of());

        subject.execute(jobExecutionContext);

        verify(repoWebhookDeliveryTransactions).sweepStuckProcessingRows(any(), eq(3));
    }

    @Test
    void aRejectedDispatchDoesNotStopTheRestOfTheBatch() throws Exception {
        RepoWebhookDelivery first = row();
        RepoWebhookDelivery second = row();
        when(repoWebhookDeliveryRepository.findDueForDispatch(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        doThrow(new RejectedExecutionException("pool full")).when(repoWebhookDispatchService)
                .dispatchAsync(first.getId());

        subject.execute(jobExecutionContext);

        verify(repoWebhookDispatchService).dispatchAsync(second.getId());
    }
}
