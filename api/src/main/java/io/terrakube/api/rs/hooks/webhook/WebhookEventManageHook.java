package io.terrakube.api.rs.hooks.webhook;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconciles the repository-level v2 webhook after an individual workspace
 * event changes. The parent webhook hook can run before Terraform (or another
 * API client) has created its events, so event mutations must independently
 * request a sync.
 */
@Slf4j
public class WebhookEventManageHook implements LifeCycleHook<WebhookEvent> {

    @Autowired
    RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @Override
    public void execute(Operation operation, TransactionPhase phase, WebhookEvent webhookEvent,
            RequestScope requestScope, Optional<ChangeSpec> changes) {
        if (phase != TransactionPhase.POSTCOMMIT) {
            return;
        }

        Webhook webhook = webhookEvent.getWebhook();
        if (webhook == null || !webhook.isMigratedV2() || !isSharedWebhookProvider(webhook)) {
            return;
        }

        try {
            Workspace workspace = webhook.getWorkspace();
            String normalizedUrl = RepoUrlNormalizer.normalize(workspace.getSource());
            if (normalizedUrl == null) {
                log.warn("Skipping repo webhook sync after {} webhook event: workspace {} has no source URL",
                        operation, workspace.getId());
                return;
            }
            String workspaceId = workspace.getId() == null ? null : workspace.getId().toString();
            repoWebhookSyncScheduler.scheduleSync(normalizedUrl, workspaceId);
        } catch (Exception e) {
            // The event change is already committed. Log the failure and let a
            // later workspace/event mutation retry the idempotent reconciliation.
            log.error("Failed to schedule repo webhook sync after {} webhook event", operation, e);
        }
    }

    private boolean isSharedWebhookProvider(Webhook webhook) {
        VcsType vcsType = webhook.getWorkspace().getVcs() == null
                ? null
                : webhook.getWorkspace().getVcs().getVcsType();
        return vcsType == VcsType.GITHUB || vcsType == VcsType.GITLAB;
    }
}
