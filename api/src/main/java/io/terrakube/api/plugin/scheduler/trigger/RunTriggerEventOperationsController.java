package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * NotificationOutbox), so there is no other way to even list one. Listing one organization's
 * events, and replaying an event, need an instance admin or {@code manage_job} in that
 * organization; the instance-wide listing is for instance admins only.
 */
@Slf4j
@RestController
@RequestMapping("/cascade/v1")
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
    @GetMapping("/organization/{organizationId}/trigger-event")
    public List<EventSummary> list(Authentication authentication, @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "FAILED") RunTriggerEventStatus status) {
        accessService.requireOperate(authentication, organizationId);
        return repository.findByStatusAndOrganizationOrderByCreatedDateDesc(status, organizationId, PageRequest.of(0, 200))
                .stream().map(RunTriggerEventOperationsController::toSummary).toList();
    }

    /** Every organization's events; instance administrators only. */
    @GetMapping("/trigger-event")
    public List<EventSummary> listAll(Authentication authentication,
            @RequestParam(defaultValue = "FAILED") RunTriggerEventStatus status) {
        accessService.requireInstanceAdmin(authentication);
        return repository.findByStatusOrderByCreatedDateDesc(status, PageRequest.of(0, 200)).stream()
                .map(RunTriggerEventOperationsController::toSummary).toList();
    }

    private static EventSummary toSummary(RunTriggerEvent event) {
        return new EventSummary(event.getId(), event.getJob().getId(), event.getJob().getWorkspace().getName(),
                event.getStatus(), event.getAttemptCount(), event.getLastError(), event.getNextAttemptAt(),
                event.getCreatedDate(), event.getJob().getOrganization().getId(),
                event.getJob().getOrganization().getName());
    }

    @PostMapping("/organization/{organizationId}/trigger-event/{eventId}/replay")
    public ReplayResult replay(Authentication authentication, @PathVariable UUID organizationId,
            @PathVariable UUID eventId) {
        accessService.requireOperate(authentication, organizationId);
        // Authorization has already passed, so an event in another organization is reported as not existing.
        if (repository.findOrganizationId(eventId).filter(organizationId::equals).isEmpty()) {
            throw new IllegalArgumentException("No such run trigger event: " + eventId);
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
