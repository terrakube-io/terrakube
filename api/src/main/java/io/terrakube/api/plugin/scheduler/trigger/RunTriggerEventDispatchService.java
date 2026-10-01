package io.terrakube.api.plugin.scheduler.trigger;

import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Claims one due {@code RunTriggerEvent} and drives it through
 * {@link RunTriggerDispatchService#dispatchInternal}, recording the outcome. Not transactional:
 * dispatch does real work across several of its own transactions and must run with nothing held
 * open around it, same as {@code RunTriggerDispatchService} itself.
 */
@Slf4j
@Service
@AllArgsConstructor
public class RunTriggerEventDispatchService {

    private final RunTriggerEventTransactions runTriggerEventTransactions;
    private final RunTriggerDispatchService runTriggerDispatchService;
    private final RunTriggerProperties properties;

    /**
     * Never throws: called once per due row from a poll loop, where one event's problem must
     * not stop the rest of the batch.
     */
    public void process(UUID eventId) {
        try {
            processInternal(eventId);
        } catch (Exception e) {
            // Reaching here means even recording the result failed - dispatchInternal's own
            // failures are caught below and turned into a PENDING/FAILED transition.
            log.error("Run trigger event {} could not be processed: {}", eventId, e.getMessage(), e);
        }
    }

    private void processInternal(UUID eventId) {
        ClaimedEvent claimed = runTriggerEventTransactions.claim(eventId);
        if (claimed == null) {
            // Already claimed by a concurrent poll (another replica, an overlapping tick), or
            // the row no longer exists. Either way, nothing left for this caller to do.
            return;
        }

        try {
            runTriggerDispatchService.dispatchInternal(claimed.jobId());
            runTriggerEventTransactions.recordResult(eventId, claimed.lastAttemptAt(),
                    RunTriggerEventStatus.PROCESSED, null, null);
        } catch (Exception e) {
            handleFailure(eventId, claimed, e);
        }
    }

    private void handleFailure(UUID eventId, ClaimedEvent claimed, Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        int maxAttempts = properties.getEventMaxAttempts();

        if (claimed.attemptCount() >= maxAttempts) {
            log.error("Run trigger event {} (job {}) permanently failed after {} attempt(s): {}",
                    eventId, claimed.jobId(), claimed.attemptCount(), message, e);
            runTriggerEventTransactions.recordResult(eventId, claimed.lastAttemptAt(),
                    RunTriggerEventStatus.FAILED, message, null);
            return;
        }

        long delayMillis = RunTriggerEventBackoff.nextDelayMillis(claimed.attemptCount(),
                properties.getEventBackoffInitialSeconds(), properties.getEventBackoffMaxSeconds());
        Date nextAttemptAt = new Date(System.currentTimeMillis() + delayMillis);
        log.warn("Run trigger event {} (job {}) failed on attempt {}/{}, retrying at {}: {}",
                eventId, claimed.jobId(), claimed.attemptCount(), maxAttempts, nextAttemptAt, message, e);
        runTriggerEventTransactions.recordResult(eventId, claimed.lastAttemptAt(),
                RunTriggerEventStatus.PENDING, message, nextAttemptAt);
    }
}
