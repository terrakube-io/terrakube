package io.terrakube.api.plugin.vcs;

import java.util.Date;

import org.junit.jupiter.api.Test;

import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoWebhookDeliveryMetricsTest {

    @Test
    void ageIsZeroWhenNoPendingRowsExist() {
        RepoWebhookDeliveryRepository repository = mock(RepoWebhookDeliveryRepository.class);
        when(repository.findOldestCreatedDateByStatus(RepoWebhookDeliveryStatus.PENDING)).thenReturn(null);

        assertThat(RepoWebhookDeliveryMetrics.oldestPendingAgeSeconds(repository)).isZero();
    }

    @Test
    void ageReflectsHowLongTheOldestPendingRowHasWaited() {
        RepoWebhookDeliveryRepository repository = mock(RepoWebhookDeliveryRepository.class);
        when(repository.findOldestCreatedDateByStatus(RepoWebhookDeliveryStatus.PENDING))
                .thenReturn(new Date(System.currentTimeMillis() - 90_000));

        double age = RepoWebhookDeliveryMetrics.oldestPendingAgeSeconds(repository);

        assertThat(age).isBetween(89.0, 95.0);
    }
}
