package io.terrakube.api.plugin.scheduler.webhook;

import java.util.List;
import java.util.UUID;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.extern.slf4j.Slf4j;

/**
 * Reconciles the shared GitHub webhook for a single repository URL: creates
 * or fetches its {@link RepoWebhook} row, then either registers/updates the
 * GitHub-side webhook (if any workspace still shares this URL with a
 * migrated v2 webhook) or deletes it as an orphan (if none do).
 *
 * <p>Runs cluster-wide at most once per repository URL at a time.
 * {@link RepoWebhookSyncScheduler} keys every job deterministically by a
 * hash of the normalized URL, and Quartz refuses to schedule a second job
 * under an already-present key, coalescing concurrent triggers for the same
 * repo onto one execution. {@code @DisallowConcurrentExecution} is a second
 * line of defense against Quartz ever firing overlapping executions of that
 * same key (e.g. on misfire recovery). This — not any locking inside
 * RepoWebhookService itself — is what actually prevents the race that used
 * to hit it directly from multiple concurrent HTTP requests.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class RepoWebhookSyncJob implements Job {

    private final RepoWebhookRepository repoWebhookRepository;

    private final WorkspaceRepository workspaceRepository;

    private final RepoWebhookService repoWebhookService;

    public RepoWebhookSyncJob(RepoWebhookRepository repoWebhookRepository, WorkspaceRepository workspaceRepository,
            RepoWebhookService repoWebhookService) {
        this.repoWebhookRepository = repoWebhookRepository;
        this.workspaceRepository = workspaceRepository;
        this.repoWebhookService = repoWebhookService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String repositoryUrl = context.getJobDetail().getJobDataMap()
                .getString(RepoWebhookSyncScheduler.DATA_KEY_REPOSITORY_URL);
        String workspaceId = context.getJobDetail().getJobDataMap()
                .getString(RepoWebhookSyncScheduler.DATA_KEY_WORKSPACE_ID);

        List<Workspace> workspaces = workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repositoryUrl);

        if (workspaces.isEmpty()) {
            repoWebhookRepository.findByRepositoryUrl(repositoryUrl)
                    .ifPresentOrElse(
                            repoWebhookService::cleanupIfOrphan,
                            () -> log.info("No RepoWebhook row and no workspaces for {}, nothing to sync", repositoryUrl));
            return;
        }

        Workspace seedWorkspace = findSeedWorkspace(workspaces, workspaceId);

        RepoWebhook repoWebhook = repoWebhookService.getOrCreateRepoWebhook(seedWorkspace);
        repoWebhookService.createOrUpdateSharedWebhook(repoWebhook);
        log.info("Synced shared webhook for {} across {} workspace(s)", repositoryUrl, workspaces.size());
    }

    private Workspace findSeedWorkspace(List<Workspace> workspaces, String workspaceId) {
        if (workspaceId != null) {
            try {
                UUID id = UUID.fromString(workspaceId);
                for (Workspace ws : workspaces) {
                    if (id.equals(ws.getId())) {
                        return ws;
                    }
                }
            } catch (IllegalArgumentException _) {
                // Not a valid UUID (or blank); fall through to the default below.
            }
        }
        // The triggering workspace is no longer in the migrated set (e.g. it
        // was deleted or unmigrated between scheduling and execution) — any
        // other workspace still sharing this URL works equally well as the
        // VCS source for a brand-new RepoWebhook row.
        return workspaces.get(0);
    }
}
