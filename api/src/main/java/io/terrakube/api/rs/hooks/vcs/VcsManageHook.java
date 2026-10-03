package io.terrakube.api.rs.hooks.vcs;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;

/**
 * Bound to Vcs.appWebhookEnabled and Vcs.webhookSecret: switching GitHub App webhook mode on or off
 * changes which repositories need a repo-level hook, so every repository of this VCS's migrated
 * workspaces is re-synced (repo hook removed when enabling, restored when disabling).
 */
@Slf4j
public class VcsManageHook implements LifeCycleHook<Vcs> {

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Vcs vcs, RequestScope requestScope,
            Optional<ChangeSpec> changes) {
        if (operation != Operation.UPDATE || phase != TransactionPhase.POSTCOMMIT) {
            return;
        }
        try {
            Set<String> scheduled = new HashSet<>();
            for (Workspace workspace : workspaceRepository.findMigratedByVcsId(vcs.getId())) {
                String normalizedUrl = RepoUrlNormalizer.normalize(workspace.getSource());
                if (normalizedUrl != null && scheduled.add(normalizedUrl)) {
                    repoWebhookSyncScheduler.scheduleSync(normalizedUrl, workspace.getId().toString());
                }
            }
        } catch (Exception e) {
            // The VCS change is already committed; the next webhook change on these workspaces re-syncs.
            log.error("Failed to schedule repo webhook sync after updating VCS {}", vcs.getId(), e);
        }
    }
}
