package io.terrakube.api.plugin.vcs;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoWebhookDispatchServiceTest {

    @Mock
    RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;

    @Mock
    RepoWebhookService repoWebhookService;

    RepoWebhookDispatchService subject;

    @BeforeEach
    void setUp() {
        subject = new RepoWebhookDispatchService(repoWebhookDeliveryTransactions, repoWebhookService, new ObjectMapper());
    }

    private ClaimedDelivery claimedDelivery(RepoWebhook repoWebhook, int attemptCount) {
        return new ClaimedDelivery(repoWebhook, "{}", "{\"x-github-event\":\"push\"}", attemptCount, new Date());
    }

    @Test
    void doesNothingWhenTheRowCannotBeClaimed() {
        UUID deliveryId = UUID.randomUUID();
        when(repoWebhookDeliveryTransactions.claim(deliveryId)).thenReturn(null);

        subject.attemptDelivery(deliveryId);

        verifyNoInteractions(repoWebhookService);
        verify(repoWebhookDeliveryTransactions, never()).recordResult(any(), any(), any(), any(), any());
    }

    @Test
    void marksProcessedAndPassesDeserializedHeadersWhenFanOutSucceeds() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setId(UUID.randomUUID());
        ClaimedDelivery claimed = claimedDelivery(repoWebhook, 1);
        when(repoWebhookDeliveryTransactions.claim(deliveryId)).thenReturn(claimed);

        subject.attemptDelivery(deliveryId);

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repoWebhookService).processClaimedDelivery(eq(repoWebhook), eq("{}"), headersCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(headersCaptor.getValue()).containsEntry("x-github-event", "push");
        verify(repoWebhookDeliveryTransactions).recordResult(eq(deliveryId), eq(claimed.lastAttemptAt()),
                eq(RepoWebhookDeliveryStatus.PROCESSED), isNull(), isNull());
    }

    @Test
    void schedulesARetryWithBackoffWhenFanOutSetupFailsBelowMaxAttempts() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        RepoWebhook repoWebhook = new RepoWebhook();
        ClaimedDelivery claimed = claimedDelivery(repoWebhook, 1);
        when(repoWebhookDeliveryTransactions.claim(deliveryId)).thenReturn(claimed);
        doThrow(new RuntimeException("boom")).when(repoWebhookService)
                .processClaimedDelivery(eq(repoWebhook), any(), anyMap());

        subject.attemptDelivery(deliveryId);

        verify(repoWebhookDeliveryTransactions).recordResult(eq(deliveryId), eq(claimed.lastAttemptAt()),
                eq(RepoWebhookDeliveryStatus.PENDING), eq("boom"), any(Date.class));
    }

    @Test
    void permanentlyFailsAfterMaxAttempts() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        RepoWebhook repoWebhook = new RepoWebhook();
        ClaimedDelivery claimed = claimedDelivery(repoWebhook, RepoWebhookDispatchService.MAX_ATTEMPTS);
        when(repoWebhookDeliveryTransactions.claim(deliveryId)).thenReturn(claimed);
        doThrow(new RuntimeException("boom")).when(repoWebhookService)
                .processClaimedDelivery(eq(repoWebhook), any(), anyMap());

        subject.attemptDelivery(deliveryId);

        verify(repoWebhookDeliveryTransactions).recordResult(eq(deliveryId), eq(claimed.lastAttemptAt()),
                eq(RepoWebhookDeliveryStatus.FAILED), eq("boom"), isNull());
    }
}
