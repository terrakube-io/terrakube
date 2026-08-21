package io.terrakube.api.plugin.notification;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;

import lombok.extern.slf4j.Slf4j;

// A separate bean (not just extra methods on NotificationDispatchService/NotificationOutboxPollerJob)
// so every DB-mutating call here goes through THIS bean's own Spring transactional proxy:
// - NotificationDispatchService.attemptDelivery calling claim()/recordResult() "this."-style
//   would be a self-invocation, and @Transactional (like @Async) is proxy-based AOP with no
//   effect on a method invoked that way from within the same class.
// - NotificationOutboxPollerJob is a Quartz Job: Spring's SchedulerFactoryBean instantiates and
//   field-autowires Job instances directly (SpringBeanJobFactory) rather than looking them up
//   from the ApplicationContext, so a Job class never gets an AOP proxy of its own at all -
//   @Transactional on a method declared directly on the Job class would silently never apply.
@Slf4j
@Service
public class NotificationOutboxTransactions {

    private final NotificationOutboxRepository notificationOutboxRepository;

    NotificationOutboxTransactions(NotificationOutboxRepository notificationOutboxRepository) {
        this.notificationOutboxRepository = notificationOutboxRepository;
    }

    @Transactional
    ClaimedOutbox claim(UUID outboxId) {
        Date now = new Date();
        // Atomic conditional UPDATE, not a pessimistic-lock read: only one concurrent caller's
        // UPDATE can match "id = :outboxId AND status = PENDING", so only one ever sees rows == 1
        // and proceeds past this method. The other sees 0 and returns immediately - no blocking,
        // no lock held while the winner goes on to make the HTTP call outside any transaction.
        int rows = notificationOutboxRepository.claimForDelivery(outboxId, NotificationOutboxStatus.PENDING,
                NotificationOutboxStatus.SENDING, now);
        if (rows == 0) {
            return null;
        }
        NotificationOutbox outbox = notificationOutboxRepository.findById(outboxId).orElse(null);
        if (outbox == null) {
            return null;
        }
        // The delivery call happens after this transaction (and its Hibernate session) has
        // closed, so a still-lazy configuration proxy would blow up with a
        // LazyInitializationException the moment the sender reads a field off it. Force it to
        // load now, while the session is still open.
        Hibernate.initialize(outbox.getConfiguration());
        return new ClaimedOutbox(outbox.getConfiguration(), outbox.getPayload(), outbox.getAttemptCount(),
                outbox.getLastAttemptAt());
    }

    @Transactional
    void recordResult(UUID outboxId, Date expectedLastAttemptAt, NotificationOutboxStatus newStatus, String lastError,
            Date nextAttemptAt) {
        // Keyed on the exact lastAttemptAt observed at claim time, not just id+SENDING: if this
        // row was reclaimed by the stuck-row sweep (this attempt outlived the sweep's threshold)
        // and re-delivered by someone else in the meantime, lastAttemptAt will have moved on and
        // this update becomes a no-op instead of clobbering the newer attempt's result.
        int updated = notificationOutboxRepository.recordDeliveryResult(outboxId, NotificationOutboxStatus.SENDING,
                expectedLastAttemptAt, newStatus, lastError, nextAttemptAt, new Date());
        if (updated == 0) {
            log.warn("Discarding stale delivery result for outbox {} - the row was reclaimed (stuck-row sweep) "
                    + "before this attempt finished; a duplicate delivery attempt may have reached the destination",
                    outboxId);
        }
    }

    // Manual "Retry" from the delivery-history UI. Public (unlike claim/recordResult) because its
    // caller, NotificationDeliveryController, lives in a different package - same reasoning as
    // sweepStuckSendingRows below.
    @Transactional
    public boolean rearmForRetry(UUID outboxId) {
        int rows = notificationOutboxRepository.rearmFailedForRetry(outboxId, NotificationOutboxStatus.FAILED,
                NotificationOutboxStatus.PENDING, new Date());
        return rows > 0;
    }

    // A row can only be stuck in SENDING if whatever claimed it (this poller's own dispatch, the
    // immediate-dispatch path, another pod) died mid-HTTP-call without ever reaching
    // recordResult - normal completion always transitions it to SENT/PENDING/FAILED. Called by
    // NotificationOutboxPollerJob every tick so a crashed pod never permanently strands a
    // notification.
    @Transactional
    public void sweepStuckSendingRows(Date cutoff, int maxAttempts) {
        int reclaimed = notificationOutboxRepository.reclaimStuckSendingRows(NotificationOutboxStatus.SENDING,
                NotificationOutboxStatus.PENDING, cutoff, maxAttempts, new Date());
        int failed = notificationOutboxRepository.failStuckSendingRowsAtMaxAttempts(NotificationOutboxStatus.SENDING,
                NotificationOutboxStatus.FAILED, cutoff, maxAttempts,
                "Delivery attempt did not complete within the stuck-row threshold; the claiming instance likely crashed",
                new Date());
        if (reclaimed > 0 || failed > 0) {
            log.warn("Notification outbox sweep reclaimed {} stuck SENDING row(s) for retry and permanently "
                    + "failed {} that had already exhausted their attempts", reclaimed, failed);
        }
    }

    // Housekeeping: without this, notification_outbox grows forever - every SENT/FAILED row from
    // every job status change with a matching trigger sits around indefinitely. Public for the
    // same cross-package reason as sweepStuckSendingRows.
    @Transactional
    public int pruneTerminalRowsOlderThan(Date cutoff) {
        int deleted = notificationOutboxRepository.deleteTerminalRowsCreatedBefore(
                List.of(NotificationOutboxStatus.SENT, NotificationOutboxStatus.FAILED), cutoff);
        if (deleted > 0) {
            log.info("Notification outbox retention sweep deleted {} row(s) older than {}", deleted, cutoff);
        }
        return deleted;
    }
}
