package io.terrakube.api.rs.hooks.step;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StepDeliveryStateHookBindingTest {

    private record Binding(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase phase) {
    }

    @Test
    void deliveryStateFieldHasPrecommitHookBindingsForCreateAndUpdate() throws NoSuchFieldException {
        Field field = Step.class.getDeclaredField("deliveryState");
        LifeCycleHookBinding[] bindings = field.getAnnotationsByType(LifeCycleHookBinding.class);

        Set<Binding> registered = Arrays.stream(bindings)
                .filter(b -> b.hook() == StepDeliveryStateHook.class)
                .map(b -> new Binding(b.operation(), b.phase()))
                .collect(Collectors.toSet());

        assertTrue(registered.contains(new Binding(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT)));
        assertTrue(registered.contains(new Binding(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT)));
    }
}
