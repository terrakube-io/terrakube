package io.terrakube.api.plugin.vcs;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared (repository level) webhook handling for GitLab. This is the GitLab
 * counterpart of {@link RepoWebhookService}: a single webhook is created per
 * repository and events are fanned out to every workspace that shares the same
 * normalized repository source.
 */
@AllArgsConstructor
@Slf4j
@Service
public class GitLabRepoWebhookService {

    RepoWebhookRepository repoWebhookRepository;
    WorkspaceRepository workspaceRepository;
    WebhookEventRepository webhookEventRepository;
    GitLabWebhookService gitLabWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;

    @Transactional
    public RepoWebhook getOrCreateRepoWebhook(Workspace workspace) {
        String normalizedUrl = RepoUrlNormalizer.normalize(workspace.getSource());
        return repoWebhookRepository.findByRepositoryUrl(normalizedUrl)
                .orElseGet(() -> {
                    try {
                        RepoWebhook repoWebhook = new RepoWebhook();
                        repoWebhook.setRepositoryUrl(normalizedUrl);
                        repoWebhook.setWebhookSecret(UUID.randomUUID().toString());
                        repoWebhook.setVcs(workspace.getVcs());
                        return repoWebhookRepository.save(repoWebhook);
                    } catch (DataIntegrityViolationException e) {
                        return repoWebhookRepository.findByRepositoryUrl(normalizedUrl)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Failed to create or find RepoWebhook for " + normalizedUrl, e));
                    }
                });
    }

    @Transactional
    public void createOrUpdateSharedWebhook(RepoWebhook repoWebhook) {
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(repoWebhook.getRepositoryUrl());

        Set<WebhookEventType> eventTypes = new HashSet<>();
        for (Workspace ws : workspaces) {
            if (ws.getWebhook() != null && ws.getWebhook().getEvents() != null) {
                for (WebhookEvent event : ws.getWebhook().getEvents()) {
                    eventTypes.add(event.getEvent());
                }
            }
        }

        if (eventTypes.isEmpty()) {
            log.warn("No webhook event types found for repo webhook {}", repoWebhook.getId());
            return;
        }

        String remoteHookId = gitLabWebhookService.createOrUpdateRepoWebhook(repoWebhook, eventTypes);
        repoWebhook.setRemoteHookId(remoteHookId);
        repoWebhookRepository.save(repoWebhook);
    }

    @Transactional
    public void cleanupIfOrphan(RepoWebhook repoWebhook) {
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(repoWebhook.getRepositoryUrl());

        if (workspaces.isEmpty()) {
            gitLabWebhookService.deleteRepoWebhook(repoWebhook);
            repoWebhookRepository.delete(repoWebhook);
            log.info("Deleted orphan repo webhook {} for {}", repoWebhook.getId(), repoWebhook.getRepositoryUrl());
        } else {
            createOrUpdateSharedWebhook(repoWebhook);
        }
    }

    @Transactional
    public void processV2Webhook(String repoWebhookId, String jsonPayload, Map<String, String> headers) {
        RepoWebhook repoWebhook = repoWebhookRepository.findById(UUID.fromString(repoWebhookId))
                .orElseThrow(() -> new IllegalArgumentException("Repo webhook not found: " + repoWebhookId));

        if (!gitLabWebhookService.verifyGitlabToken(headers, repoWebhook.getWebhookSecret())) {
            log.error("Token verification failed for repo webhook {}", repoWebhookId);
            throw new SecurityException("GitLab token verification failed");
        }

        WebhookResult webhookResult = gitLabWebhookService.parseGitlabPayload(jsonPayload, headers,
                repoWebhook.getVcs(), repoWebhook.getRepositoryUrl());

        if (!webhookResult.isValid()) {
            log.warn("Invalid webhook result for repo webhook {}", repoWebhookId);
            return;
        }

        String normalizedUrl = repoWebhook.getRepositoryUrl();
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(normalizedUrl);

        log.info("Processing v2 gitlab webhook for {} workspaces on repo {}", workspaces.size(), normalizedUrl);

        for (Workspace workspace : workspaces) {
            try {
                processWorkspaceWebhook(workspace, webhookResult);
            } catch (Exception e) {
                log.error("Error processing v2 gitlab webhook for workspace {}: {}", workspace.getName(), e.getMessage(), e);
            }
        }
    }

    private void processWorkspaceWebhook(Workspace workspace, WebhookResult webhookResult) {
        if (workspace.getWebhook() == null) {
            log.warn("Workspace {} has no webhook despite being returned by migrated query", workspace.getName());
            return;
        }

        try {
            String templateId = webhookResult.isRelease()
                    ? WebhookEventMatcher.findTemplateIdRelease(webhookResult, workspace.getWebhook(),
                            webhookEventRepository)
                    : WebhookEventMatcher.findTemplateId(webhookResult, workspace.getWebhook(),
                            webhookEventRepository);

            log.info("V2 gitlab webhook event {} for workspace {}, using template {}",
                    webhookResult.getNormalizedEvent(), workspace.getName(), templateId);

            Job job = new Job();
            job.setTemplateReference(templateId);
            job.setRefresh(true);
            job.setPlanChanges(true);
            job.setRefreshOnly(false);
            job.setOverrideBranch(webhookResult.isRelease()
                    ? "refs/tags/" + webhookResult.getBranch()
                    : webhookResult.getBranch());
            job.setOrganization(workspace.getOrganization());
            job.setWorkspace(workspace);
            job.setCreatedBy(webhookResult.getCreatedBy());
            job.setUpdatedBy(webhookResult.getCreatedBy());
            Date triggerDate = new Date(System.currentTimeMillis());
            job.setCreatedDate(triggerDate);
            job.setUpdatedDate(triggerDate);
            job.setVia(webhookResult.getVia());
            job.setCommitId(webhookResult.getCommit());
            Job savedJob = jobRepository.save(job);
            if (!webhookResult.isRelease() && workspace.getVcs() != null) {
                gitLabWebhookService.sendCommitStatus(savedJob, JobStatus.pending);
            }
            scheduleJobService.createJobContext(savedJob);
        } catch (IllegalArgumentException e) {
            log.info("No matching template for workspace {} on event {}: {}", workspace.getName(),
                    webhookResult.getNormalizedEvent(), e.getMessage());
        } catch (Exception e) {
            log.error("Error creating job for workspace {}", workspace.getName(), e);
        }
    }
}
