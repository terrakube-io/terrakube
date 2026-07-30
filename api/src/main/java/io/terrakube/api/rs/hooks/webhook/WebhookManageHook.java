package io.terrakube.api.rs.hooks.webhook;

import java.util.Optional;

import org.apache.hc.core5.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpClientErrorException;
import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebhookManageHook implements LifeCycleHook<Webhook> {
    @Autowired
    WebhookService webhookService;

    @Autowired
    GitHubWebhookService gitHubWebhookService;

    @Autowired
    RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Webhook elideEntity, RequestScope requestScope,
            Optional<ChangeSpec> changes) {
        switch (operation) {
            case CREATE:
            case UPDATE:
                switch (phase) {
                    case PRECOMMIT:
                        try {
                            if (isMigratedV2GitHub(elideEntity)) {
                                // Delete the old per-workspace hook, if any — this is
                                // workspace-specific cleanup, not the shared-repo setup
                                // that used to race, so it stays inline here.
                                if (elideEntity.getRemoteHookId() != null && !elideEntity.getRemoteHookId().isEmpty()) {
                                    gitHubWebhookService.deleteWebhook(elideEntity.getWorkspace(), elideEntity.getRemoteHookId());
                                    elideEntity.setRemoteHookId(null);
                                }
                            } else {
                                webhookService.createOrUpdateWorkspaceWebhook(elideEntity);
                            }
                        } catch (HttpClientErrorException e) {
                            throw new WebhookManagementException(HttpStatus.SC_FAILED_DEPENDENCY,
                                    buildPermissionErrorMessage(elideEntity, e));
                        } catch (Exception e) {
                            throw new WebhookManagementException(HttpStatus.SC_FAILED_DEPENDENCY,
                                    "Failed to create/update webhook: " + e.getMessage());
                        }
                        break;

                    case POSTCOMMIT:
                        // Only now is the workspace's migrated Webhook/WebhookEvent
                        // data durably committed and visible to the job's own query.
                        //
                        // Scheduled whenever the workspace is GitHub-backed at all,
                        // not just when currently migrated-v2: a revert (migratedV2
                        // true -> false) needs this too, so the job can notice this
                        // workspace no longer shares the URL and either resync the
                        // remaining migrated workspaces' event types or, if this was
                        // the last one, clean up the now-orphaned shared webhook. The
                        // job itself is cheap and already a no-op when there's
                        // nothing to do, so this isn't gated on isMigratedV2GitHub.
                        try {
                            if (isGitHub(elideEntity)) {
                                scheduleSync(elideEntity);
                            }
                        } catch (Exception e) {
                            log.error("Failed to schedule repo webhook sync after create/update", e);
                        }
                        break;

                    default:
                        break;
                }
                break;
            case DELETE:
                switch (phase) {
                    case POSTCOMMIT:
                        try {
                            if (isMigratedV2GitHub(elideEntity)) {
                                scheduleSync(elideEntity);
                            } else {
                                webhookService.deleteWorkspaceWebhook(elideEntity);
                            }
                        } catch (Exception e) {
                            throw new WebhookManagementException(HttpStatus.SC_FAILED_DEPENDENCY,
                                    "Failed to delete webhook: " + e.getMessage());
                        }
                        break;

                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }

    private boolean isMigratedV2GitHub(Webhook elideEntity) {
        return elideEntity.isMigratedV2() && isGitHub(elideEntity);
    }

    private boolean isGitHub(Webhook elideEntity) {
        return elideEntity.getWorkspace().getVcs() != null
                && elideEntity.getWorkspace().getVcs().getVcsType() == VcsType.GITHUB;
    }

    private void scheduleSync(Webhook elideEntity) {
        String normalizedUrl = RepoUrlNormalizer.normalize(elideEntity.getWorkspace().getSource());
        if (normalizedUrl == null) {
            log.warn("Skipping repo webhook sync for workspace {}: workspace has no source URL",
                    elideEntity.getWorkspace().getId());
            return;
        }
        String workspaceId = elideEntity.getWorkspace().getId() == null ? null : elideEntity.getWorkspace().getId().toString();
        repoWebhookSyncScheduler.scheduleSync(normalizedUrl, workspaceId);
    }

    private String buildPermissionErrorMessage(Webhook webhook, HttpClientErrorException e) {
        int statusCode = e.getStatusCode().value();
        if (statusCode == HttpStatus.SC_FORBIDDEN || statusCode == HttpStatus.SC_UNAUTHORIZED) {
            boolean hasPrWorkflow = webhook.getEvents() != null && webhook.getEvents().stream()
                    .anyMatch(WebhookEvent::isPrWorkflowEnabled);
            String required = hasPrWorkflow
                    ? "'Webhooks: write' permission, and 'Pull requests: write' permission because PR Workflow is enabled on this webhook"
                    : "'Webhooks: write' permission";
            return "The VCS connection does not have sufficient permissions to create/update this webhook on the linked repository. Please ensure the VCS connection has "
                    + required + ".";
        }
        return "Failed to create/update webhook: " + e.getMessage();
    }

}
