package io.terrakube.api.rs.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding;

import io.terrakube.api.rs.hooks.webhook.WebhookEventManageHook;

class WebhookEventLifeCycleHookBindingTest {

    private record Binding(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase phase) {
    }

    @Test
    void registersEventHookForPostCommitMutations() {
        Set<Binding> registered = java.util.Arrays.stream(
                WebhookEvent.class.getAnnotationsByType(LifeCycleHookBinding.class))
                .filter(binding -> binding.hook() == WebhookEventManageHook.class)
                .map(binding -> new Binding(binding.operation(), binding.phase()))
                .collect(Collectors.toSet());

        assertThat(registered).containsExactlyInAnyOrder(
                new Binding(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT),
                new Binding(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT),
                new Binding(LifeCycleHookBinding.Operation.DELETE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT));
    }
}
