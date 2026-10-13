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
 * action-only. Allowed for an instance admin, or a member of a team with {@code manage_job} in the
 * organization in the path ({@link CascadeOperationsAccessService}); the cascade or node must belong
 * to that organization.
 */
@Slf4j
@RestController
@RequestMapping("/cascade/v1")
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

    /** Whether the caller is an instance administrator (instance-wide pages). */
    @GetMapping("/capabilities")
    public Map<String, Boolean> instanceCapabilities(Authentication authentication) {
        return Map.of("instanceAdmin", accessService.isInstanceAdmin(authentication));
    }

    /** What the caller may do in this organization, and whether they are an instance administrator. */
    @GetMapping("/organization/{organizationId}/capabilities")
    public Map<String, Boolean> capabilities(Authentication authentication, @PathVariable UUID organizationId) {
        return Map.of(
                "operate", accessService.canOperate(authentication, organizationId),
                "instanceAdmin", accessService.isInstanceAdmin(authentication));
    }

    @PostMapping("/organization/{organizationId}/cascade/{cascadeId}/cancel")
    public CascadeActionResult cancel(Authentication authentication, @PathVariable UUID organizationId,
            @PathVariable UUID cascadeId) {
        accessService.requireOperate(authentication, organizationId);
        requireCascadeIn(organizationId, cascadeId);
        RunCascade cascade = coordinatorService.cancelCascade(cascadeId);
        log.info("Cascade {} cancelled by an operator", cascadeId);
        return new CascadeActionResult(cascade.getId(), cascade.getStatus().name());
    }

    @PostMapping("/organization/{organizationId}/cascade/{cascadeId}/node/{nodeId}/retry")
    public NodeActionResult retry(Authentication authentication, @PathVariable UUID organizationId,
            @PathVariable UUID cascadeId, @PathVariable UUID nodeId) {
        accessService.requireOperate(authentication, organizationId);
        requireNodeIn(organizationId, cascadeId, nodeId);
        Job job = coordinatorService.retryNode(nodeId);
        log.info("Cascade node {} retried by an operator as job {}", nodeId, job.getId());
        return new NodeActionResult(nodeId, RunCascadeNodeStatus.RUNNING.name(), job.getId());
    }

    @PostMapping("/organization/{organizationId}/cascade/{cascadeId}/node/{nodeId}/resume")
    public NodeActionResult resume(Authentication authentication, @PathVariable UUID organizationId,
            @PathVariable UUID cascadeId, @PathVariable UUID nodeId) {
        accessService.requireOperate(authentication, organizationId);
        requireNodeIn(organizationId, cascadeId, nodeId);
        Optional<Job> job = coordinatorService.resumeNode(nodeId);
        log.info("Cascade node {} resumed by an operator{}", nodeId,
                job.map(j -> " as job " + j.getId()).orElse(" - still not ready, left paused"));
        String status = job.isPresent() ? RunCascadeNodeStatus.RUNNING.name() : RunCascadeNodeStatus.BLOCKED.name();
        return new NodeActionResult(nodeId, status, job.map(Job::getId).orElse(null));
    }

    /** Authorization has already passed, so a mismatch is reported as the resource not existing there. */
    private void requireCascadeIn(UUID organizationId, UUID cascadeId) {
        if (cascadeRepository.findOrganizationId(cascadeId).filter(organizationId::equals).isEmpty()) {
            throw new IllegalArgumentException("No such cascade: " + cascadeId);
        }
    }

    private void requireNodeIn(UUID organizationId, UUID cascadeId, UUID nodeId) {
        requireCascadeIn(organizationId, cascadeId);
        if (!nodeRepository.existsByIdAndCascade_Id(nodeId, cascadeId)) {
            throw new IllegalArgumentException("No such cascade node: " + nodeId);
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
