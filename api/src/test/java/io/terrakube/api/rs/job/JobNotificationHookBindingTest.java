package io.terrakube.api.rs.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding;

import io.terrakube.api.rs.hooks.notification.JobNotificationHook;

class JobNotificationHookBindingTest {

    private record Binding(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase phase) {
    }

    @Test
    void registersJobNotificationHookOnTheStatusFieldForUpdatePrecommitAndPostcommit() throws NoSuchFieldException {
        LifeCycleHookBinding[] bindings = Job.class.getDeclaredField("status")
                .getAnnotationsByType(LifeCycleHookBinding.class);

        Set<Binding> registered = java.util.Arrays.stream(bindings)
                .filter(b -> b.hook() == JobNotificationHook.class)
                .map(b -> new Binding(b.operation(), b.phase()))
                .collect(Collectors.toSet());

        assertThat(registered).containsExactlyInAnyOrder(
                new Binding(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT),
                new Binding(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT));
    }

    @Test
    void doesNotRegisterJobNotificationHookAtTheClassLevel() {
        LifeCycleHookBinding[] bindings = Job.class.getAnnotationsByType(LifeCycleHookBinding.class);

        boolean anyOnClass = java.util.Arrays.stream(bindings).anyMatch(b -> b.hook() == JobNotificationHook.class);

        assertThat(anyOnClass).isFalse();
    }
}
