package io.terrakube.api.plugin.vcs;

import java.util.Date;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

@Component
public class RepoWebhookDeliveryMetrics {

    private final RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;
    private final MeterRegistry meterRegistry;

    public RepoWebhookDeliveryMetrics(RepoWebhookDeliveryRepository repoWebhookDeliveryRepository,
            MeterRegistry meterRegistry) {
        this.repoWebhookDeliveryRepository = repoWebhookDeliveryRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("webhook.delivery.queue.depth", repoWebhookDeliveryRepository,
                        repo -> repo.countByStatus(RepoWebhookDeliveryStatus.PENDING))
                .description("Number of repo_webhook_delivery rows currently PENDING")
                .register(meterRegistry);

        Gauge.builder("webhook.delivery.queue.oldest.age.seconds", repoWebhookDeliveryRepository,
                        RepoWebhookDeliveryMetrics::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest PENDING repo_webhook_delivery row, 0 if none")
                .register(meterRegistry);
    }

    static double oldestPendingAgeSeconds(RepoWebhookDeliveryRepository repo) {
        Date oldest = repo.findOldestCreatedDateByStatus(RepoWebhookDeliveryStatus.PENDING);
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - oldest.getTime()) / 1000.0);
    }
}
