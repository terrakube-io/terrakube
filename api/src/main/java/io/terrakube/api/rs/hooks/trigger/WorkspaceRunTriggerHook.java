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
 * Rejects a run trigger that would close a loop in the organization's graph.
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

        graphValidationService.validateAcyclic(
                trigger.getOrganization().getId(),
                trigger.getId(),
                trigger.getSourceWorkspace().getId(),
                trigger.getDestinationWorkspace().getId());
    }
}
