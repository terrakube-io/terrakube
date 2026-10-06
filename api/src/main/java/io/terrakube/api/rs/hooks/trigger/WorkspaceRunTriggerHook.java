package io.terrakube.api.rs.hooks.trigger;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.scheduler.trigger.WorkspaceGraphValidationService;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Rejects a run trigger that would close a loop in the organization's graph, or that would push
 * its source workspace's outbound fan-out past the configured limit.
 *
 * Runs at PRECOMMIT: the row is already flushed by then, so the validation excludes the edge
 * being written and aborts the transaction when it finds a path back. Deriving the
 * organization is not done here - that needs @PrePersist, since PRECOMMIT is too late for a
 * NOT NULL column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceRunTriggerHook implements LifeCycleHook<WorkspaceRunTrigger> {

    private final WorkspaceGraphValidationService graphValidationService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
                        LifeCycleHookBinding.TransactionPhase phase,
                        WorkspaceRunTrigger trigger,
                        RequestScope requestScope,
                        Optional<ChangeSpec> changes) {

        if (trigger.getSourceWorkspace() == null || trigger.getDestinationWorkspace() == null
                || trigger.getOrganization() == null) {
            return;
        }

        if (topologyChangeApplies(operation, changes)) {
            graphValidationService.validateAcyclic(
                    trigger.getOrganization().getId(),
                    trigger.getId(),
                    trigger.getSourceWorkspace().getId(),
                    trigger.getDestinationWorkspace().getId());
        }

        if (fanOutLimitApplies(operation, changes)) {
            graphValidationService.validateFanOutLimit(
                    trigger.getSourceWorkspace().getId(),
                    trigger.isEnabled());
        }
    }

    /**
     * True on create, and on an update that changes which workspaces the edge connects - the
     * only way an existing edge's place in the graph can change. A benign update (template,
     * enabled) touches neither field, so it skips the organization lock and DFS entirely.
     */
    private boolean topologyChangeApplies(LifeCycleHookBinding.Operation operation, Optional<ChangeSpec> changes) {
        if (operation == LifeCycleHookBinding.Operation.CREATE) {
            return true;
        }
        if (operation != LifeCycleHookBinding.Operation.UPDATE) {
            return false;
        }
        return changes.filter(c -> "sourceWorkspace".equals(c.getFieldName())
                        || "destinationWorkspace".equals(c.getFieldName()))
                .isPresent();
    }

    /**
     * True on create, and on an update that either flips {@code enabled} to true or repoints
     * {@code sourceWorkspace} - both ways an edge can start counting against a source it
     * previously didn't. Elide invokes this hook once per changed field, with {@code changes}
     * holding that one field's {@code ChangeSpec}, so the two update cases are checked
     * independently rather than as one combined change.
     */
    private boolean fanOutLimitApplies(LifeCycleHookBinding.Operation operation, Optional<ChangeSpec> changes) {
        if (operation == LifeCycleHookBinding.Operation.CREATE) {
            return true;
        }
        if (operation != LifeCycleHookBinding.Operation.UPDATE) {
            return false;
        }
        return changes.filter(c -> "enabled".equals(c.getFieldName()) && Boolean.TRUE.equals(c.getModified()))
                .or(() -> changes.filter(c -> "sourceWorkspace".equals(c.getFieldName())))
                .isPresent();
    }
}
