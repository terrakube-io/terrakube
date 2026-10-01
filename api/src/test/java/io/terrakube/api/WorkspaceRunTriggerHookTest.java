package io.terrakube.api;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import io.terrakube.api.plugin.scheduler.trigger.WorkspaceGraphValidationService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.trigger.WorkspaceRunTriggerHook;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Which operations the cycle check and the fan-out check each run on - the latter is create-only. */
class WorkspaceRunTriggerHookTest {

    private WorkspaceGraphValidationService graphValidationService;
    private WorkspaceRunTriggerHook hook;

    @BeforeEach
    void setUp() {
        graphValidationService = mock(WorkspaceGraphValidationService.class);
        hook = new WorkspaceRunTriggerHook(graphValidationService);
    }

    private WorkspaceRunTrigger trigger(boolean enabled) {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        Workspace source = new Workspace();
        source.setId(UUID.randomUUID());
        Workspace destination = new Workspace();
        destination.setId(UUID.randomUUID());

        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
        trigger.setId(UUID.randomUUID());
        trigger.setSourceWorkspace(source);
        trigger.setDestinationWorkspace(destination);
        trigger.setOrganization(organization);
        trigger.setEnabled(enabled);
        return trigger;
    }

    @Test
    void createChecksBothCycleAndFanOut() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.empty());

        verify(graphValidationService).validateAcyclic(trigger.getOrganization().getId(), trigger.getId(),
                trigger.getSourceWorkspace().getId(), trigger.getDestinationWorkspace().getId());
        verify(graphValidationService).validateFanOutLimit(trigger.getSourceWorkspace().getId(), true);
    }

    /** An update still has to stay acyclic, but isn't blocked by the fan-out limit. */
    @Test
    void updateChecksCycleButNotFanOut() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.empty());

        verify(graphValidationService).validateAcyclic(any(), any(), any(), any());
        verify(graphValidationService, never()).validateFanOutLimit(any(), anyBoolean());
    }

    @Test
    void incompleteTriggerChecksNeither() {
        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();

        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.empty());

        verify(graphValidationService, never()).validateAcyclic(any(), any(), any(), any());
        verify(graphValidationService, never()).validateFanOutLimit(any(), anyBoolean());
    }
}
