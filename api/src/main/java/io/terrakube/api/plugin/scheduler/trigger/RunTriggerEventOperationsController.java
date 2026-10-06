package io.terrakube.api.plugin.scheduler.trigger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator replay of a durable run trigger event (#3628) that exhausted its retry attempts and
 * landed FAILED. {@link RunTriggerEventTransactions#rearmForRetry} has existed since that slice
 * for exactly this purpose but had no HTTP caller yet; this is it. Same admin gate as
 * {@link RunCascadeOperationsController}.
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/run-trigger-events")
public class RunTriggerEventOperationsController {

    private final RunTriggerEventTransactions transactions;

    public RunTriggerEventOperationsController(RunTriggerEventTransactions transactions) {
        this.transactions = transactions;
    }

    public record ReplayResult(UUID eventId, boolean rearmed) {}

    @PostMapping("/{eventId}/replay")
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public ReplayResult replay(@PathVariable UUID eventId) {
        boolean rearmed = transactions.rearmForRetry(eventId);
        if (rearmed) {
            log.info("Admin replayed run trigger event {}", eventId);
        } else {
            log.info("Admin replay of run trigger event {} was a no-op - not FAILED (already retried, or "
                    + "never reached a terminal state)", eventId);
        }
        return new ReplayResult(eventId, rearmed);
    }
}
