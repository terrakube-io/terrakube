package io.terrakube.api.rs.hooks.webhook;

import java.util.Optional;

import org.apache.hc.core5.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.Webhook;

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
    RepoWebhookService repoWebhookService;

    @Autowired
    GitHubWebhookService gitHubWebhookService;

    @Autowired
    RepoWebhookRepository repoWebhookRepository;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Webhook elideEntity, RequestScope requestScope,
            Optional<ChangeSpec> changes) {
        switch (operation) {
            case CREATE:
            case UPDATE:
                switch (phase) {
                    case PRECOMMIT:
                        try {
                            if (elideEntity.isMigratedV2()
                                    && elideEntity.getWorkspace().getVcs() != null
                                    && elideEntity.getWorkspace().getVcs().getVcsType() == VcsType.GITHUB) {
                                // V2 shared webhook path
                                RepoWebhook repoWebhook = repoWebhookService.getOrCreateRepoWebhook(elideEntity.getWorkspace());
                                repoWebhookService.createOrUpdateSharedWebhook(repoWebhook);
                                // Delete old per-workspace hook if it exists
                                if (elideEntity.getRemoteHookId() != null && !elideEntity.getRemoteHookId().isEmpty()) {
                                    gitHubWebhookService.deleteWebhook(elideEntity.getWorkspace(), elideEntity.getRemoteHookId());
                                    elideEntity.setRemoteHookId(null);
                                }
                            } else {
                                webhookService.createOrUpdateWorkspaceWebhook(elideEntity);
                            }
                        } catch (Exception e) {
                            throw new WebhookManagementException(HttpStatus.SC_FAILED_DEPENDENCY,
                                    "Failed to create/update webhook: " + e.getMessage());
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
                            if (elideEntity.isMigratedV2()
                                    && elideEntity.getWorkspace().getVcs() != null
                                    && elideEntity.getWorkspace().getVcs().getVcsType() == VcsType.GITHUB) {
                                String normalizedUrl = RepoUrlNormalizer.normalize(elideEntity.getWorkspace().getSource());
                                repoWebhookRepository.findByRepositoryUrl(normalizedUrl)
                                        .ifPresent(repoWebhookService::cleanupIfOrphan);
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

}
