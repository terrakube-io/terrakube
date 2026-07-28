package io.terrakube.api.rs.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding;

import io.terrakube.api.rs.hooks.webhook.WebhookManageHook;

/**
 * WebhookManageHookTest exercises the hook's execute() method directly, which
 * bypasses Elide's own annotation-driven dispatch entirely — it can't catch a
 * missing @LifeCycleHookBinding on the entity itself. That exact gap once let
 * CREATE/UPDATE POSTCOMMIT scheduling code sit dead: it compiled, its unit
 * tests passed (calling execute() directly), but Elide never actually invoked
 * it for real CREATE/UPDATE requests because no binding registered it for
 * POSTCOMMIT — only DELETE POSTCOMMIT was registered. Confirmed live: no
 * RepoWebhook row and no remoteHookId were ever produced for freshly created
 * webhooks despite every other test passing.
 */
class WebhookLifeCycleHookBindingTest {

    private record Binding(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase phase) {
    }

    @Test
    void registersWebhookManageHookForEveryOperationAndCommitPhaseItNeeds() {
        LifeCycleHookBinding[] bindings = Webhook.class.getAnnotationsByType(LifeCycleHookBinding.class);

        Set<Binding> registered = java.util.Arrays.stream(bindings)
                .filter(b -> b.hook() == WebhookManageHook.class)
                .map(b -> new Binding(b.operation(), b.phase()))
                .collect(Collectors.toSet());

        // PRECOMMIT: synchronous, request-scoped work (v1 hook create/update,
        // and deleting the old v1 hook when migrating to v2).
        assertThat(registered).contains(
                new Binding(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT),
                new Binding(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT));

        // POSTCOMMIT: schedules the async shared-webhook sync, and must run
        // only after the workspace's migrated Webhook/WebhookEvent rows are
        // durably committed and visible to the job's own query.
        assertThat(registered).contains(
                new Binding(LifeCycleHookBinding.Operation.CREATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT),
                new Binding(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT),
                new Binding(LifeCycleHookBinding.Operation.DELETE, LifeCycleHookBinding.TransactionPhase.POSTCOMMIT));
    }
}
