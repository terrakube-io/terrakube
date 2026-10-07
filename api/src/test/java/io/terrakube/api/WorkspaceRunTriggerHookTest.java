package io.terrakube.api;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.security.ChangeSpec;
import io.terrakube.api.plugin.scheduler.trigger.MissingPlanOnlyTemplateException;
import io.terrakube.api.plugin.scheduler.trigger.WorkspaceGraphValidationService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.trigger.WorkspaceRunTriggerHook;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerOnDestroyPolicy;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** An update that touches neither topology nor fan-out fields checks neither. */
    @Test
    void updateOfAnUnrelatedFieldChecksNeither() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "template", null, null)));

        verify(graphValidationService, never()).validateAcyclic(any(), any(), any(), any());
        verify(graphValidationService, never()).validateFanOutLimit(any(), anyBoolean());
    }

    /** Repointing either end of an edge is the only way its place in the graph can change. */
    @Test
    void updateChangingSourceWorkspaceChecksCycle() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "sourceWorkspace", UUID.randomUUID(), trigger.getSourceWorkspace())));

        verify(graphValidationService).validateAcyclic(trigger.getOrganization().getId(), trigger.getId(),
                trigger.getSourceWorkspace().getId(), trigger.getDestinationWorkspace().getId());
    }

    @Test
    void updateChangingDestinationWorkspaceChecksCycle() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "destinationWorkspace", UUID.randomUUID(), trigger.getDestinationWorkspace())));

        verify(graphValidationService).validateAcyclic(trigger.getOrganization().getId(), trigger.getId(),
                trigger.getSourceWorkspace().getId(), trigger.getDestinationWorkspace().getId());
    }

    /** Re-enabling a disabled edge is exactly the case the create-only check used to miss. */
    @Test
    void updateFlippingEnabledToTrueChecksFanOut() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "enabled", false, true)));

        verify(graphValidationService).validateFanOutLimit(trigger.getSourceWorkspace().getId(), true);
    }

    /** Flipping enabled off is never a fan-out risk, regardless of the ChangeSpec shape. */
    @Test
    void updateFlippingEnabledToFalseSkipsFanOut() {
        WorkspaceRunTrigger trigger = trigger(false);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "enabled", true, false)));

        verify(graphValidationService, never()).validateFanOutLimit(any(), anyBoolean());
    }

    /** A superuser repointing sourceWorkspace must count against the new source too. */
    @Test
    void updateChangingSourceWorkspaceChecksFanOut() {
        WorkspaceRunTrigger trigger = trigger(true);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "sourceWorkspace", UUID.randomUUID(), trigger.getSourceWorkspace())));

        verify(graphValidationService).validateFanOutLimit(trigger.getSourceWorkspace().getId(), true);
    }

    /** The UI requires a plan-only template client-side; the hook is what enforces it server-side. */
    @Test
    void createWithPlanOnlyAndNoTemplateIsRejected() {
        WorkspaceRunTrigger trigger = trigger(true);
        trigger.setOnDestroy(RunTriggerOnDestroyPolicy.PLAN_ONLY);

        assertThatThrownBy(() -> hook.execute(LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT, trigger, null, Optional.empty()))
                .isInstanceOf(MissingPlanOnlyTemplateException.class);
    }

    @Test
    void createWithPlanOnlyAndATemplateIsAccepted() {
        WorkspaceRunTrigger trigger = trigger(true);
        trigger.setOnDestroy(RunTriggerOnDestroyPolicy.PLAN_ONLY);
        Template planTemplate = new Template();
        planTemplate.setId(UUID.randomUUID());
        trigger.setOnDestroyPlanTemplate(planTemplate);

        hook.execute(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.empty());
    }

    /** Flipping an existing TRIGGER edge over to PLAN_ONLY without a template must still be caught. */
    @Test
    void updateChangingOnDestroyToPlanOnlyWithNoTemplateIsRejected() {
        WorkspaceRunTrigger trigger = trigger(true);
        trigger.setOnDestroy(RunTriggerOnDestroyPolicy.PLAN_ONLY);

        assertThatThrownBy(() -> hook.execute(LifeCycleHookBinding.Operation.UPDATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT, trigger, null,
                Optional.of(new ChangeSpec(null, "onDestroy", RunTriggerOnDestroyPolicy.TRIGGER,
                        RunTriggerOnDestroyPolicy.PLAN_ONLY))))
                .isInstanceOf(MissingPlanOnlyTemplateException.class);
    }

    /** An update to an unrelated field on an already-invalid row must not be newly rejected by it. */
    @Test
    void updateOfAnUnrelatedFieldSkipsThePlanOnlyCheck() {
        WorkspaceRunTrigger trigger = trigger(true);
        trigger.setOnDestroy(RunTriggerOnDestroyPolicy.PLAN_ONLY);

        hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                trigger, null, Optional.of(new ChangeSpec(null, "template", null, null)));
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
