package io.terrakube.api.plugin.scheduler.webhook;

import java.util.Date;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import io.terrakube.api.plugin.vcs.RepoWebhookDeliveryTransactions;
import io.terrakube.api.plugin.vcs.RepoWebhookDispatchService;
import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import lombok.extern.slf4j.Slf4j;

// Safety net for the immediate-dispatch path (RepoWebhookService.acceptV2Webhook /
// RepoWebhookDispatchService.dispatchAsync): picks up any delivery that's still PENDING because
// the dispatch executor's queue was full, the instance that accepted it crashed before dispatching,
// or a retry's backoff (nextAttemptAt) has now elapsed. Also reclaims deliveries stuck PROCESSING
// from a crashed fan-out.
@Slf4j
@Component
@DisallowConcurrentExecution
public class RepoWebhookDeliveryPollerJob implements Job {

    private static final int MAX_ATTEMPTS = 3;

    private final RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;
    private final RepoWebhookDispatchService repoWebhookDispatchService;
    private final RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;
    private final int batchSize;
    private final long stuckProcessingThresholdMillis;

    public RepoWebhookDeliveryPollerJob(RepoWebhookDeliveryRepository repoWebhookDeliveryRepository,
            RepoWebhookDispatchService repoWebhookDispatchService,
            RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions,
            @Value("${io.terrakube.webhook.poller.batchSize:200}") int batchSize,
            @Value("${io.terrakube.webhook.poller.stuckProcessingThresholdMinutes:5}") long stuckProcessingThresholdMinutes) {
        this.repoWebhookDeliveryRepository = repoWebhookDeliveryRepository;
        this.repoWebhookDispatchService = repoWebhookDispatchService;
        this.repoWebhookDeliveryTransactions = repoWebhookDeliveryTransactions;
        this.batchSize = batchSize;
        this.stuckProcessingThresholdMillis = stuckProcessingThresholdMinutes * 60_000L;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // Quartz's SchedulerFactoryBean instantiates and field-autowires Job instances directly
        // rather than looking them up from the ApplicationContext, so this class never gets an
        // AOP proxy of its own - any @Transactional declared directly here would silently never
        // apply. Both DB-mutating steps below are therefore delegated to
        // RepoWebhookDeliveryTransactions, a real Spring-managed bean whose own proxy the calls
        // below go through.
        repoWebhookDeliveryTransactions.sweepStuckProcessingRows(
                new Date(System.currentTimeMillis() - stuckProcessingThresholdMillis), MAX_ATTEMPTS);
        dispatchDueRows();
    }

    private void dispatchDueRows() {
        List<RepoWebhookDelivery> due = repoWebhookDeliveryRepository.findDueForDispatch(RepoWebhookDeliveryStatus.PENDING,
                new Date(), PageRequest.of(0, batchSize));

        int dispatched = 0;
        for (RepoWebhookDelivery delivery : due) {
            try {
                repoWebhookDispatchService.dispatchAsync(delivery.getId());
                dispatched++;
            } catch (RejectedExecutionException e) {
                // The dispatch executor's queue is full - this row is still PENDING (this
                // submission never claimed it) and will be picked up again on the next tick.
                log.warn("Repo webhook dispatch executor rejected delivery {}, will retry next poll cycle",
                        delivery.getId());
            }
        }
        if (dispatched > 0) {
            log.info("Repo webhook delivery poller dispatched {} pending row(s)", dispatched);
        }
    }
}
