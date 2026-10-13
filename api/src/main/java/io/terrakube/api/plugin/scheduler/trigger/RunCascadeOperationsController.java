package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator actions on a cascade the coordinator left needing one: cancel, retry a failed/skipped
 * node, or resume one that's blocked. Read access to a cascade's current state is already
 * JSON:API/GraphQL (the {@code run_cascade}/{@code run_cascade_node} entities), so this stays
 * action-only - same split {@link io.terrakube.api.plugin.scheduler.reconciliation
 * .SchedulerReconciliationController} draws between its {@code GET} report and {@code POST}
 * apply. Allowed for an instance admin, or a member of a team with {@code manage_job} in the
 * organization the cascade belongs to ({@link CascadeOperationsAccessService}).
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/cascades")
public class RunCascadeOperationsController {

    private final RunCascadeCoordinatorService coordinatorService;
    private final CascadeOperationsAccessService accessService;
    private final RunCascadeRepository cascadeRepository;
    private final RunCascadeNodeRepository nodeRepository;

    public RunCascadeOperationsController(RunCascadeCoordinatorService coordinatorService,
            CascadeOperationsAccessService accessService, RunCascadeRepository cascadeRepository,
            RunCascadeNodeRepository nodeRepository) {
        this.coordinatorService = coordinatorService;
        this.accessService = accessService;
        this.cascadeRepository = cascadeRepository;
        this.nodeRepository = nodeRepository;
    }

    public record CascadeActionResult(UUID cascadeId, String status) {}

    public record NodeActionResult(UUID nodeId, String status, Integer jobId) {}

    /**
     * What the caller may do: {@code operate} for the given organization (or instance-wide when none
     * is given) and whether they are an instance admin.
     */
    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities(Authentication authentication,
            @RequestParam(required = false) UUID organizationId) {
        boolean instanceAdmin = accessService.isInstanceAdmin(authentication);
        boolean operate = organizationId == null ? instanceAdmin : accessService.canOperate(authentication, organizationId);
        return Map.of("operate", operate, "instanceAdmin", instanceAdmin);
    }

    @PostMapping("/{cascadeId}/cancel")
    public CascadeActionResult cancel(Authentication authentication, @PathVariable UUID cascadeId) {
        authorize(authentication, cascadeRepository.findOrganizationId(cascadeId));
        RunCascade cascade = coordinatorService.cancelCascade(cascadeId);
        log.info("Cascade {} cancelled by an operator", cascadeId);
        return new CascadeActionResult(cascade.getId(), cascade.getStatus().name());
    }

    @PostMapping("/nodes/{nodeId}/retry")
    public NodeActionResult retry(Authentication authentication, @PathVariable UUID nodeId) {
        authorize(authentication, nodeRepository.findOrganizationId(nodeId));
        Job job = coordinatorService.retryNode(nodeId);
        log.info("Cascade node {} retried by an operator as job {}", nodeId, job.getId());
        return new NodeActionResult(nodeId, RunCascadeNodeStatus.RUNNING.name(), job.getId());
    }

    @PostMapping("/nodes/{nodeId}/resume")
    public NodeActionResult resume(Authentication authentication, @PathVariable UUID nodeId) {
        authorize(authentication, nodeRepository.findOrganizationId(nodeId));
        Optional<Job> job = coordinatorService.resumeNode(nodeId);
        log.info("Cascade node {} resumed by an operator{}", nodeId,
                job.map(j -> " as job " + j.getId()).orElse(" - still not ready, left paused"));
        String status = job.isPresent() ? RunCascadeNodeStatus.RUNNING.name() : RunCascadeNodeStatus.BLOCKED.name();
        return new NodeActionResult(nodeId, status, job.map(Job::getId).orElse(null));
    }

    /**
     * An unknown id is a 404 for an instance admin (so a typo is reported as one) and a 403 for anyone
     * else, so the endpoint can't be used to probe which ids exist.
     */
    private void authorize(Authentication authentication, Optional<UUID> organizationId) {
        if (organizationId.isPresent()) {
            accessService.requireOperate(authentication, organizationId.get());
        } else {
            accessService.requireInstanceAdmin(authentication);
        }
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
