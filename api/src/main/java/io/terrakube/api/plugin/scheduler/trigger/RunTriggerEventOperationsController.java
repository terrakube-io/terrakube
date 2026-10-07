package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Operator visibility into, and replay of, a durable run trigger event (#3628). The entity
 * itself isn't an Elide resource (see its own javadoc - internal queue plumbing, same as
 * NotificationOutbox), so there is no other way to even list one. Same admin gate throughout,
 * and the same GET-report / POST-apply split {@code SchedulerReconciliationController} draws.
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/run-trigger-events")
public class RunTriggerEventOperationsController {

    private final RunTriggerEventRepository repository;
    private final RunTriggerEventTransactions transactions;

    public RunTriggerEventOperationsController(RunTriggerEventRepository repository,
            RunTriggerEventTransactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    public record ReplayResult(UUID eventId, boolean rearmed) {}

    public record EventSummary(UUID id, int jobId, String workspaceName, RunTriggerEventStatus status,
            int attemptCount, String lastError, Date nextAttemptAt, Date createdDate) {}

    /** Newest first, capped rather than paginated - this is an exception queue, not a log. */
    @GetMapping
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public List<EventSummary> list(@RequestParam(defaultValue = "FAILED") RunTriggerEventStatus status) {
        return repository.findByStatusOrderByCreatedDateDesc(status, PageRequest.of(0, 200)).stream()
                .map(RunTriggerEventOperationsController::toSummary)
                .toList();
    }

    private static EventSummary toSummary(RunTriggerEvent event) {
        return new EventSummary(event.getId(), event.getJob().getId(), event.getJob().getWorkspace().getName(),
                event.getStatus(), event.getAttemptCount(), event.getLastError(), event.getNextAttemptAt(),
                event.getCreatedDate());
    }

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
