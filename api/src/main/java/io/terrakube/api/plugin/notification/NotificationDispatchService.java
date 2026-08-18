package io.terrakube.api.plugin.notification;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.notification.sender.NotificationDeliveryException;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryService;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NotificationDispatchService {

    static final int MAX_ATTEMPTS = 3;
    private static final long ONE_MINUTE_MILLIS = 60_000L;
    private static final long FIVE_MINUTES_MILLIS = 5 * ONE_MINUTE_MILLIS;

    private final NotificationOutboxTransactions notificationOutboxTransactions;
    private final NotificationDeliveryService notificationDeliveryService;

    public NotificationDispatchService(NotificationOutboxTransactions notificationOutboxTransactions,
            NotificationDeliveryService notificationDeliveryService) {
        this.notificationOutboxTransactions = notificationOutboxTransactions;
        this.notificationDeliveryService = notificationDeliveryService;
    }

    @Async("notificationDispatchExecutor")
    public void dispatchAsync(UUID outboxId) {
        attemptDelivery(outboxId);
    }

    // claim() and recordResult() each open their own short transaction on a separate bean
    // (NotificationOutboxTransactions) - the HTTP delivery call in between runs with no
    // transaction (and therefore no DB connection or row lock) held for its duration.
    // Previously the pessimistic lock and the outbound call to Slack/Teams/webhook shared one
    // transaction, so a slow or hanging destination could hold a pooled DB connection for up to
    // the sender's full timeout (10s connect + 30s read).
    public void attemptDelivery(UUID outboxId) {
        ClaimedOutbox claimed = notificationOutboxTransactions.claim(outboxId);
        if (claimed == null) {
            return;
        }
        DeliveryOutcome outcome = deliver(claimed);

        NotificationOutboxStatus newStatus;
        Date nextAttemptAt = null;
        String lastError = null;
        if (outcome.delivered()) {
            newStatus = NotificationOutboxStatus.SENT;
        } else {
            lastError = outcome.errorMessage();
            if (!outcome.retryable()) {
                newStatus = NotificationOutboxStatus.FAILED;
                log.warn("Notification outbox {} permanently failed (non-retryable failure): {}", outboxId, lastError);
            } else if (claimed.attemptCount() >= MAX_ATTEMPTS) {
                newStatus = NotificationOutboxStatus.FAILED;
                log.warn("Notification outbox {} permanently failed after {} attempts: {}", outboxId,
                        claimed.attemptCount(), lastError);
            } else {
                newStatus = NotificationOutboxStatus.PENDING;
                // A server-supplied Retry-After overrides the fixed attemptCount-based backoff -
                // e.g. Slack's 429 tells us exactly how long its rate limit window is, which may
                // be longer (or shorter) than our default schedule.
                long delayMillis = outcome.retryAfter() != null ? outcome.retryAfter().toMillis()
                        : backoffMillis(claimed.attemptCount());
                nextAttemptAt = new Date(System.currentTimeMillis() + delayMillis);
                log.info("Notification outbox {} attempt {} failed, will retry: {}", outboxId, claimed.attemptCount(),
                        lastError);
            }
        }

        notificationOutboxTransactions.recordResult(outboxId, claimed.lastAttemptAt(), newStatus, lastError,
                nextAttemptAt);
    }

    private DeliveryOutcome deliver(ClaimedOutbox claimed) {
        try {
            notificationDeliveryService.deliver(claimed.configuration(), claimed.payload());
            return DeliveryOutcome.success();
        } catch (NotificationDeliveryException e) {
            return DeliveryOutcome.failure(e);
        }
    }

    private long backoffMillis(int attemptCount) {
        return attemptCount <= 1 ? ONE_MINUTE_MILLIS : FIVE_MINUTES_MILLIS;
    }

    private record DeliveryOutcome(boolean delivered, String errorMessage, boolean retryable, Duration retryAfter) {
        static DeliveryOutcome success() {
            return new DeliveryOutcome(true, null, true, null);
        }

        static DeliveryOutcome failure(NotificationDeliveryException e) {
            return new DeliveryOutcome(false, e.getMessage(), e.isRetryable(), e.getRetryAfter());
        }
    }
}
