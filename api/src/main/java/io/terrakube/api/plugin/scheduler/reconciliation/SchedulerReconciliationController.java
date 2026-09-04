package io.terrakube.api.plugin.scheduler.reconciliation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator-safe recovery for jobs stuck non-terminal with no remaining executable step
 * (design doc §3.8). {@code GET} reports; {@code POST} applies only deterministic
 * completed/failed/cancelled/rejected transitions, and only when explicitly confirmed. Anomalies
 * are reported but never auto-transitioned. Instance-owner / internal token required.
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/scheduler/reconciliation")
public class SchedulerReconciliationController {

    private final JobReconciliationService reconciliationService;

    public SchedulerReconciliationController(JobReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    public record ApplyRequest(boolean confirm, List<Integer> jobIds) {}

    @GetMapping
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public List<ReconciliationResult> report() {
        return reconciliationService.report();
    }

    @PostMapping
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public ResponseEntity<List<ReconciliationResult>> apply(@RequestBody ApplyRequest request) {
        if (!request.confirm()) {
            return ResponseEntity.badRequest().build();
        }
        List<ReconciliationResult> applied = reconciliationService.report().stream()
                .filter(r -> request.jobIds() == null || request.jobIds().contains(r.jobId()))
                .filter(r -> r.derivedOutcome() != null && r.derivedOutcome().isTerminalTransition())
                .map(r -> {
                    ReconciliationResult result = reconciliationService.reconcile(r.jobId(), false);
                    log.info("Admin reconciliation applied: job {} {} -> {} ({})",
                            r.jobId(), result.currentStatus(), result.targetStatus(), result.disposition());
                    return result;
                })
                .toList();
        return ResponseEntity.ok(applied);
    }
}
