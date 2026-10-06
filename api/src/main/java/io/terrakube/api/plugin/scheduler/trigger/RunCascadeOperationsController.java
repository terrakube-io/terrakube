package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Operator actions on a cascade the coordinator left needing one: cancel, retry a failed/skipped
 * node, or resume one that's blocked. Read access to a cascade's current state is already
 * JSON:API/GraphQL (the {@code run_cascade}/{@code run_cascade_node} entities), so this stays
 * action-only - same split {@link io.terrakube.api.plugin.scheduler.reconciliation
 * .SchedulerReconciliationController} draws between its {@code GET} report and {@code POST}
 * apply. Instance-owner / internal token required, same gate.
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/cascades")
public class RunCascadeOperationsController {

    private final RunCascadeCoordinatorService coordinatorService;

    public RunCascadeOperationsController(RunCascadeCoordinatorService coordinatorService) {
        this.coordinatorService = coordinatorService;
    }

    public record CascadeActionResult(UUID cascadeId, String status) {}

    public record NodeActionResult(UUID nodeId, String status, Integer jobId) {}

    @PostMapping("/{cascadeId}/cancel")
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public CascadeActionResult cancel(@PathVariable UUID cascadeId) {
        RunCascade cascade = coordinatorService.cancelCascade(cascadeId);
        log.info("Admin cancelled cascade {}", cascadeId);
        return new CascadeActionResult(cascade.getId(), cascade.getStatus().name());
    }

    @PostMapping("/nodes/{nodeId}/retry")
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public NodeActionResult retry(@PathVariable UUID nodeId) {
        Job job = coordinatorService.retryNode(nodeId);
        log.info("Admin retried cascade node {} as job {}", nodeId, job.getId());
        return new NodeActionResult(nodeId, RunCascadeNodeStatus.RUNNING.name(), job.getId());
    }

    @PostMapping("/nodes/{nodeId}/resume")
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public NodeActionResult resume(@PathVariable UUID nodeId) {
        Optional<Job> job = coordinatorService.resumeNode(nodeId);
        log.info("Admin resumed cascade node {}{}", nodeId,
                job.map(j -> " as job " + j.getId()).orElse(" - still not ready, left PENDING"));
        String status = job.isPresent() ? RunCascadeNodeStatus.RUNNING.name() : RunCascadeNodeStatus.PENDING.name();
        return new NodeActionResult(nodeId, status, job.map(Job::getId).orElse(null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
