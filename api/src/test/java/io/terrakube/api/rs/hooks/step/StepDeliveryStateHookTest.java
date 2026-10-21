package io.terrakube.api.rs.hooks.step;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;
import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepDeliveryStateHookTest {

    private StepDeliveryStateHook hook;
    private Step step;

    @BeforeEach
    void setUp() {
        hook = new StepDeliveryStateHook();
        step = new Step();
        step.setId(UUID.randomUUID());
    }

    @Test
    void rejectsUpdateTransitioningToRecovered() {
        ChangeSpec changeSpec = mock(ChangeSpec.class);
        when(changeSpec.getModified()).thenReturn(DeliveryState.RECOVERED);
        Optional<ChangeSpec> changes = Optional.of(changeSpec);

        StepDeliveryStateException ex = assertThrows(StepDeliveryStateException.class, () ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, step, null, changes));

        assertEquals(400, ex.getStatus());
    }

    @Test
    void rejectsUpdateTransitioningToExpired() {
        ChangeSpec changeSpec = mock(ChangeSpec.class);
        when(changeSpec.getModified()).thenReturn(DeliveryState.EXPIRED);
        Optional<ChangeSpec> changes = Optional.of(changeSpec);

        StepDeliveryStateException ex = assertThrows(StepDeliveryStateException.class, () ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, step, null, changes));

        assertEquals(400, ex.getStatus());
    }

    @Test
    void rejectsUpdateTransitioningToStringRecovered() {
        ChangeSpec changeSpec = mock(ChangeSpec.class);
        when(changeSpec.getModified()).thenReturn("RECOVERED");
        Optional<ChangeSpec> changes = Optional.of(changeSpec);

        StepDeliveryStateException ex = assertThrows(StepDeliveryStateException.class, () ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, step, null, changes));

        assertEquals(400, ex.getStatus());
    }

    @Test
    void allowsUpdateTransitioningToAcked() {
        ChangeSpec changeSpec = mock(ChangeSpec.class);
        when(changeSpec.getModified()).thenReturn(DeliveryState.ACKED);
        Optional<ChangeSpec> changes = Optional.of(changeSpec);

        assertDoesNotThrow(() ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, step, null, changes));
    }

    @Test
    void allowsUpdateTransitioningToSpooled() {
        ChangeSpec changeSpec = mock(ChangeSpec.class);
        when(changeSpec.getModified()).thenReturn(DeliveryState.SPOOLED);
        Optional<ChangeSpec> changes = Optional.of(changeSpec);

        assertDoesNotThrow(() ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, step, null, changes));
    }

    @Test
    void rejectsCreateWithRecoveredDeliveryState() {
        step.setDeliveryState(DeliveryState.RECOVERED);
        Optional<ChangeSpec> changes = Optional.empty();

        StepDeliveryStateException ex = assertThrows(StepDeliveryStateException.class, () ->
                hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, step, null, changes));

        assertEquals(400, ex.getStatus());
    }

    @Test
    void allowsCreateWithDefaultDeliveryState() {
        step.setDeliveryState(DeliveryState.NOT_DURABLE);
        Optional<ChangeSpec> changes = Optional.empty();

        assertDoesNotThrow(() ->
                hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, step, null, changes));
    }
}
