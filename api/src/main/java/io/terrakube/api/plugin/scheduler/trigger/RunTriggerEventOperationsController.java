package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator visibility into, and replay of, a durable run trigger event (#3628). The entity
 * itself isn't an Elide resource (see its own javadoc - internal queue plumbing, same as
 * NotificationOutbox), so there is no other way to even list one. Listing one organization's
 * events, and replaying an event, need an instance admin or {@code manage_job} in that
 * organization; the instance-wide listing is for instance admins only. Same GET-report /
 * POST-apply split {@code SchedulerReconciliationController} draws.
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/run-trigger-events")
public class RunTriggerEventOperationsController {

    private final RunTriggerEventRepository repository;
    private final RunTriggerEventTransactions transactions;
    private final CascadeOperationsAccessService accessService;

    public RunTriggerEventOperationsController(RunTriggerEventRepository repository,
            RunTriggerEventTransactions transactions, CascadeOperationsAccessService accessService) {
        this.repository = repository;
        this.transactions = transactions;
        this.accessService = accessService;
    }

    public record ReplayResult(UUID eventId, boolean rearmed) {}

    public record EventSummary(UUID id, int jobId, String workspaceName, RunTriggerEventStatus status,
            int attemptCount, String lastError, Date nextAttemptAt, Date createdDate, UUID organizationId,
            String organizationName) {}

    /** Newest first, capped rather than paginated - this is an exception queue, not a log. */
    @GetMapping
    public List<EventSummary> list(Authentication authentication,
            @RequestParam(defaultValue = "FAILED") RunTriggerEventStatus status,
            @RequestParam(required = false) UUID organizationId) {
        PageRequest page = PageRequest.of(0, 200);
        List<RunTriggerEvent> events;
        if (organizationId == null) {
            accessService.requireInstanceAdmin(authentication);
            events = repository.findByStatusOrderByCreatedDateDesc(status, page);
        } else {
            accessService.requireOperate(authentication, organizationId);
            events = repository.findByStatusAndOrganizationOrderByCreatedDateDesc(status, organizationId, page);
        }
        return events.stream().map(RunTriggerEventOperationsController::toSummary).toList();
    }

    private static EventSummary toSummary(RunTriggerEvent event) {
        return new EventSummary(event.getId(), event.getJob().getId(), event.getJob().getWorkspace().getName(),
                event.getStatus(), event.getAttemptCount(), event.getLastError(), event.getNextAttemptAt(),
                event.getCreatedDate(), event.getJob().getOrganization().getId(),
                event.getJob().getOrganization().getName());
    }

    @PostMapping("/{eventId}/replay")
    public ReplayResult replay(Authentication authentication, @PathVariable UUID eventId) {
        // An unknown id is only reported as such to an instance admin; anyone else gets a 403.
        Optional<UUID> organizationId = repository.findOrganizationId(eventId);
        if (organizationId.isPresent()) {
            accessService.requireOperate(authentication, organizationId.get());
        } else {
            accessService.requireInstanceAdmin(authentication);
        }
        boolean rearmed = transactions.rearmForRetry(eventId);
        if (rearmed) {
            log.info("Run trigger event {} replayed by an operator", eventId);
        } else {
            log.info("Replay of run trigger event {} was a no-op - not FAILED (already retried, or "
                    + "never reached a terminal state)", eventId);
        }
        return new ReplayResult(eventId, rearmed);
    }
}
