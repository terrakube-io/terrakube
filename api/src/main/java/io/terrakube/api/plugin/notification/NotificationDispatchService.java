package io.terrakube.api.plugin.notification;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.cost.InfracostContextExtractor;
import io.terrakube.api.plugin.cost.InfracostSummary;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryException;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryService;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationMessageStyle;
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
    private final InfracostContextExtractor infracostContextExtractor;
    private final StepRepository stepRepository;
    private final NotificationPayloadRenderer notificationPayloadRenderer;

    public NotificationDispatchService(NotificationOutboxTransactions notificationOutboxTransactions,
            NotificationDeliveryService notificationDeliveryService,
            InfracostContextExtractor infracostContextExtractor,
            StepRepository stepRepository,
            NotificationPayloadRenderer notificationPayloadRenderer) {
        this.notificationOutboxTransactions = notificationOutboxTransactions;
        this.notificationDeliveryService = notificationDeliveryService;
        this.infracostContextExtractor = infracostContextExtractor;
        this.stepRepository = stepRepository;
        this.notificationPayloadRenderer = notificationPayloadRenderer;
    }

    @Async("notificationDispatchExecutor")
    public void dispatchAsync(UUID outboxId) {
        attemptDelivery(outboxId);
    }

    // claim() and recordResult() each open their own short transaction on a separate bean
    // (NotificationOutboxTransactions) - the cost-context read and HTTP delivery call in
    // between run with no transaction (and therefore no DB connection or row lock) held.
    public void attemptDelivery(UUID outboxId) {
        ClaimedOutbox claimed = notificationOutboxTransactions.claim(outboxId);
        if (claimed == null) {
            return;
        }
        String payload = resolveDispatchPayload(claimed);
        DeliveryOutcome outcome = deliver(claimed.configuration(), payload);

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

    private DeliveryOutcome deliver(NotificationConfiguration configuration, String payload) {
        try {
            notificationDeliveryService.deliver(configuration, payload);
            return DeliveryOutcome.success();
        } catch (NotificationDeliveryException e) {
            return DeliveryOutcome.failure(e);
        }
    }

    private String resolveDispatchPayload(ClaimedOutbox claimed) {
        NotificationConfiguration configuration = claimed.configuration();
        if (configuration == null || claimed.jobId() == null
                || !shouldAttachCostSummary(claimed.jobStatus())
                || !needsCostSummary(configuration)) {
            return claimed.payload();
        }
        try {
            List<Step> steps = stepRepository.findByJobId(claimed.jobId());
            Optional<InfracostSummary> summaryOpt = infracostContextExtractor.extract(claimed.jobId(), steps);
            if (summaryOpt.isEmpty()) {
                return claimed.payload();
            }
            return notificationPayloadRenderer.enrichWithCost(
                    configuration.getChannelType(), claimed.payload(), summaryOpt.get());
        } catch (Exception e) {
            log.warn("Unable to resolve Infracost summary at dispatch for job {}: {}",
                    claimed.jobId(), e.getMessage());
            return claimed.payload();
        }
    }

    private static boolean needsCostSummary(NotificationConfiguration configuration) {
        if (configuration.getChannelType() == NotificationChannelType.WEBHOOK) {
            return true;
        }
        return configuration.getMessageStyle() != NotificationMessageStyle.SIMPLE;
    }

    private static boolean shouldAttachCostSummary(JobStatus status) {
        return status == JobStatus.completed
                || status == JobStatus.waitingApproval
                || status == JobStatus.failed
                || status == JobStatus.noChanges;
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
