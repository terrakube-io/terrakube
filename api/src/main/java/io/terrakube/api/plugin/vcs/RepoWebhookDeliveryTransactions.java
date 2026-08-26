package io.terrakube.api.plugin.vcs;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import lombok.extern.slf4j.Slf4j;

// A separate bean (not extra methods on RepoWebhookService/RepoWebhookDispatchService/the poller
// job) for the same reason as NotificationOutboxTransactions: every DB-mutating call here needs to
// go through THIS bean's own Spring transactional proxy. RepoWebhookDispatchService calling
// claim()/recordResult() "this."-style would be a self-invocation with no transactional effect,
// and RepoWebhookDeliveryPollerJob is a Quartz Job - Spring's SchedulerFactoryBean instantiates and
// field-autowires Job instances directly rather than looking them up from the ApplicationContext,
// so a Job class never gets an AOP proxy of its own and @Transactional declared directly on it
// would silently never apply.
@Slf4j
@Service
public class RepoWebhookDeliveryTransactions {

    private final RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;

    RepoWebhookDeliveryTransactions(RepoWebhookDeliveryRepository repoWebhookDeliveryRepository) {
        this.repoWebhookDeliveryRepository = repoWebhookDeliveryRepository;
    }

    // Commits in its own transaction before returning, so the caller (RepoWebhookService.acceptV2Webhook)
    // can safely trigger the immediate-dispatch path right after this returns without racing the
    // insert's own commit - see acceptV2Webhook for why it deliberately has no @Transactional of
    // its own wrapping this call.
    @Transactional
    public UUID enqueue(RepoWebhook repoWebhook, String payload, String headers) {
        RepoWebhookDelivery delivery = new RepoWebhookDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setRepoWebhook(repoWebhook);
        delivery.setPayload(payload);
        delivery.setHeaders(headers);
        delivery.setStatus(RepoWebhookDeliveryStatus.PENDING);
        repoWebhookDeliveryRepository.save(delivery);
        return delivery.getId();
    }

    // Atomic conditional UPDATE, not a pessimistic-lock read: only one concurrent caller's UPDATE
    // can match "id = :deliveryId AND status = PENDING", so only one ever sees rows == 1 and
    // proceeds past this method. The other sees 0 and returns immediately - no blocking, no lock
    // held while the winner goes on to fan out to every workspace outside any transaction.
    @Transactional
    public ClaimedDelivery claim(UUID deliveryId) {
        Date now = new Date();
        int rows = repoWebhookDeliveryRepository.claimForDelivery(deliveryId, RepoWebhookDeliveryStatus.PENDING,
                RepoWebhookDeliveryStatus.PROCESSING, now);
        if (rows == 0) {
            return null;
        }
        RepoWebhookDelivery delivery = repoWebhookDeliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return null;
        }
        // The fan-out happens after this transaction (and its Hibernate session) has closed, so a
        // still-lazy repoWebhook (and its vcs) proxy would blow up with a
        // LazyInitializationException the moment RepoWebhookService reads a field off it. Force
        // both to load now, while the session is still open.
        Hibernate.initialize(delivery.getRepoWebhook());
        if (delivery.getRepoWebhook().getVcs() != null) {
            Hibernate.initialize(delivery.getRepoWebhook().getVcs());
        }
        return new ClaimedDelivery(delivery.getRepoWebhook(), delivery.getPayload(), delivery.getHeaders(),
                delivery.getAttemptCount(), delivery.getLastAttemptAt());
    }

    // Keyed on the exact lastAttemptAt observed at claim time, not just id+PROCESSING: if this row
    // was reclaimed by the stuck-row sweep (this attempt outlived the sweep's threshold) and
    // re-dispatched by someone else in the meantime, lastAttemptAt will have moved on and this
    // update becomes a no-op instead of clobbering the newer attempt's result.
    @Transactional
    public void recordResult(UUID deliveryId, Date expectedLastAttemptAt, RepoWebhookDeliveryStatus newStatus,
            String lastError, Date nextAttemptAt) {
        int updated = repoWebhookDeliveryRepository.recordDeliveryResult(deliveryId, RepoWebhookDeliveryStatus.PROCESSING,
                expectedLastAttemptAt, newStatus, lastError, nextAttemptAt, new Date());
        if (updated == 0) {
            log.warn("Discarding stale delivery result for repo webhook delivery {} - the row was reclaimed "
                    + "(stuck-row sweep) before this attempt finished; a duplicate fan-out attempt may have run",
                    deliveryId);
        }
    }

    // A row can only be stuck in PROCESSING if whatever claimed it (the immediate-dispatch path,
    // another instance) died mid-fan-out without ever reaching recordResult - normal completion
    // always transitions it to PROCESSED/PENDING/FAILED. Called by RepoWebhookDeliveryPollerJob
    // every tick so a crashed instance never permanently strands a delivery.
    @Transactional
    public void sweepStuckProcessingRows(Date cutoff, int maxAttempts) {
        int reclaimed = repoWebhookDeliveryRepository.reclaimStuckProcessingRows(RepoWebhookDeliveryStatus.PROCESSING,
                RepoWebhookDeliveryStatus.PENDING, cutoff, maxAttempts, new Date());
        int failed = repoWebhookDeliveryRepository.failStuckProcessingRowsAtMaxAttempts(RepoWebhookDeliveryStatus.PROCESSING,
                RepoWebhookDeliveryStatus.FAILED, cutoff, maxAttempts,
                "Delivery fan-out did not complete within the stuck-row threshold; the claiming instance likely crashed",
                new Date());
        if (reclaimed > 0 || failed > 0) {
            log.warn("Repo webhook delivery sweep reclaimed {} stuck PROCESSING row(s) for retry and permanently "
                    + "failed {} that had already exhausted their attempts", reclaimed, failed);
        }
    }

    // Housekeeping: without this, repo_webhook_delivery grows forever - every PROCESSED/FAILED row
    // from every push/PR event on every shared webhook sits around indefinitely.
    @Transactional
    public int pruneTerminalRowsOlderThan(Date cutoff) {
        int deleted = repoWebhookDeliveryRepository.deleteTerminalRowsCreatedBefore(
                List.of(RepoWebhookDeliveryStatus.PROCESSED, RepoWebhookDeliveryStatus.FAILED), cutoff);
        if (deleted > 0) {
            log.info("Repo webhook delivery retention sweep deleted {} row(s) older than {}", deleted, cutoff);
        }
        return deleted;
    }
}
