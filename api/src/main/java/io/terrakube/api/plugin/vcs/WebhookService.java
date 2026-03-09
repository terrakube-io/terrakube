package io.terrakube.api.plugin.vcs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.VcsWebhookRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.VcsWebhook;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final VcsWebhookRepository vcsWebhookRepository;
    private final WorkspaceRepository workspaceRepository;
    private final GitHubWebhookService gitHubWebhookService;
    private final GitLabWebhookService gitLabWebhookService;
    private final BitBucketWebhookService bitBucketWebhookService;
    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;
    private final ObjectMapper objectMapper;

    @Value("${io.terrakube.hostname}")
    private String hostname;

    public WebhookService(
            WebhookRepository webhookRepository,
            WebhookEventRepository webhookEventRepository,
            VcsWebhookRepository vcsWebhookRepository,
            WorkspaceRepository workspaceRepository,
            GitHubWebhookService gitHubWebhookService,
            GitLabWebhookService gitLabWebhookService,
            BitBucketWebhookService bitBucketWebhookService,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            ObjectMapper objectMapper) {
        this.webhookRepository = webhookRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.vcsWebhookRepository = vcsWebhookRepository;
        this.workspaceRepository = workspaceRepository;
        this.gitHubWebhookService = gitHubWebhookService;
        this.gitLabWebhookService = gitLabWebhookService;
        this.bitBucketWebhookService = bitBucketWebhookService;
        this.jobRepository = jobRepository;
        this.scheduleJobService = scheduleJobService;
        this.objectMapper = objectMapper;
    }

    public Webhook getWebhookById(String webhookId) {
        return webhookRepository.findById(UUID.fromString(webhookId))
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + webhookId));
    }

    @Transactional
    public String processWebhook(String webhookId, String jsonPayload, Map<String, String> headers) {
        String result = "";
        Webhook webhook = webhookRepository.getReferenceById(UUID.fromString(webhookId));
        if (webhook == null) {
            throw new IllegalArgumentException("Webhook not found");
        }
        Workspace workspace = webhook.getWorkspace();
        Vcs vcs = workspace.getVcs();

        // if the VCS is empty we cannot process the webhook
        if (vcs == null) {
            log.error("VCS not found for workspace {} with id {}", workspace.getName(), workspace.getId());
            return result;
        }

        WebhookResult webhookResult = new WebhookResult();
        String base64WorkspaceId = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        switch (vcs.getVcsType()) {
            case GITHUB:
                webhookResult = gitHubWebhookService.processWebhook(jsonPayload, headers,
                        base64WorkspaceId, vcs);
                break;
            case GITLAB:
                webhookResult = gitLabWebhookService.processWebhook(jsonPayload, headers,
                        base64WorkspaceId, workspace);
                break;
            case BITBUCKET:
                webhookResult = bitBucketWebhookService.processWebhook(jsonPayload, headers,
                        base64WorkspaceId);
                break;
            default:
                break;
        }

        log.info("webhook result {}", webhookResult);

        if (!webhookResult.isValid()
                || webhookResult.getEvent().equals(String.valueOf(WebhookEventType.PING).toLowerCase()))
            return result;

        try {
            String templateId = webhookResult.isRelease() ? findTemplateIdRelease(webhookResult, webhook) : findTemplateId(webhookResult, webhook);
            log.info("webhook event {} for workspace {}, using template with id {}", webhookResult.getNormalizedEvent(),
                    webhook.getWorkspace().getName(), templateId);
            Job job = new Job();
            job.setTemplateReference(templateId);
            job.setRefresh(true);
            job.setPlanChanges(true);
            job.setRefreshOnly(false);
            job.setOverrideBranch(webhookResult.isRelease() ? "refs/tags/" + webhookResult.getBranch() : webhookResult.getBranch());
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
            if (!webhookResult.isRelease())
                sendCommitStatus(savedJob);
            scheduleJobService.createJobContext(savedJob);
        } catch (Exception e) {
            log.error("Error creating the job", e);
        }
        return result;
    }

    @Transactional
    public void createOrUpdateWorkspaceWebhook(Webhook webhook) {
        Workspace workspace = webhook.getWorkspace();
        if (workspace.getVcs() == null) {
            log.warn("There is no VCS defined for workspace {}, skipping webhook creation", workspace.getName());
            throw new IllegalArgumentException("No VCS defined for workspace");
        }

        Vcs vcs = workspace.getVcs();

        // GitHub uses shared VcsWebhook mode for new webhooks
        if (vcs.getVcsType() == io.terrakube.api.rs.vcs.VcsType.GITHUB) {
            // If this is an update to an existing legacy webhook, keep using legacy mode
            if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
                String webhookRemoteId = gitHubWebhookService.createOrUpdateWebhook(workspace, webhook);
                if (webhookRemoteId == null || webhookRemoteId.isEmpty()) {
                    throw new IllegalArgumentException("Error updating the legacy webhook");
                }
                webhook.setRemoteHookId(webhookRemoteId);
                return;
            }

            createOrReuseSharedWebhook(workspace, webhook, vcs);
            return;
        }

        // GitLab and Bitbucket continue with legacy per-workspace mode
        String webhookRemoteId = "";
        switch (vcs.getVcsType()) {
            case GITLAB:
                webhookRemoteId = gitLabWebhookService.createOrUpdateWebhook(workspace, webhook);
                break;
            case BITBUCKET:
                webhookRemoteId = bitBucketWebhookService.createOrUpdateWebhook(workspace, webhook);
                break;
            default:
                break;
        }

        if (webhookRemoteId.isEmpty()) {
            log.error("Error creating the webhook");
            throw new IllegalArgumentException("Error creating/updating the webhook");
        }

        webhook.setRemoteHookId(webhookRemoteId);
    }

    private void createOrReuseSharedWebhook(Workspace workspace, Webhook webhook, Vcs vcs) {
        String normalizedUrl = WebhookServiceBase.normalizeRepositoryUrl(workspace.getSource());
        VcsWebhook vcsWebhook = vcsWebhookRepository.findByVcsAndRepositoryUrl(vcs, normalizedUrl)
                .orElse(null);

        if (vcsWebhook == null) {
            vcsWebhook = new VcsWebhook();
            vcsWebhook.setVcs(vcs);
            vcsWebhook.setRepositoryUrl(normalizedUrl);
            vcsWebhook.setSecret(UUID.randomUUID().toString());
            vcsWebhook = vcsWebhookRepository.save(vcsWebhook);

            String callbackUrl = String.format("https://%s/webhook/v1/vcs/%s", hostname, vcsWebhook.getId().toString());
            List<String> events = collectEventTypes(webhook);
            String remoteHookId = gitHubWebhookService.createSharedWebhook(vcs, workspace.getSource(), callbackUrl, vcsWebhook.getSecret(), events);
            if (remoteHookId == null) {
                vcsWebhookRepository.delete(vcsWebhook);
                throw new IllegalArgumentException("Error creating shared GitHub webhook");
            }
            vcsWebhook.setRemoteHookId(remoteHookId);
            vcsWebhookRepository.save(vcsWebhook);
            log.info("Created shared VcsWebhook {} for repo {}", vcsWebhook.getId(), normalizedUrl);
        } else {
            // Shared webhook exists — update event types on GitHub if needed
            List<String> allEvents = collectAllEventTypesForRepo(vcs, normalizedUrl, webhook);
            gitHubWebhookService.updateSharedWebhookEvents(vcs, workspace.getSource(), vcsWebhook.getRemoteHookId(), allEvents);
            log.info("Reusing shared VcsWebhook {} for workspace {}", vcsWebhook.getId(), workspace.getName());
        }

        // Workspace webhook doesn't own a remote hook in shared mode
        webhook.setRemoteHookId(null);
    }

    private List<String> collectEventTypes(Webhook webhook) {
        return webhook.getEvents().stream()
                .map(WebhookEvent::getEvent)
                .distinct()
                .map(e -> String.valueOf(e).toLowerCase())
                .collect(Collectors.toList());
    }

    private List<String> collectAllEventTypesForRepo(Vcs vcs, String normalizedUrl, Webhook newWebhook) {
        Set<String> events = new HashSet<>();
        // Collect from existing workspaces on this repo
        for (Workspace ws : workspaceRepository.findByVcsAndWebhookIsNotNull(vcs)) {
            if (WebhookServiceBase.normalizeRepositoryUrl(ws.getSource()).equals(normalizedUrl)) {
                ws.getWebhook().getEvents().forEach(e -> events.add(String.valueOf(e.getEvent()).toLowerCase()));
            }
        }
        // Add events from the new webhook being created
        if (newWebhook != null && newWebhook.getEvents() != null) {
            newWebhook.getEvents().forEach(e -> events.add(String.valueOf(e.getEvent()).toLowerCase()));
        }
        return List.copyOf(events);
    }

    @Transactional
    public void deleteWorkspaceWebhook(Webhook webhook) {
        Workspace workspace = webhook.getWorkspace();
        if (workspace.getVcs() == null) {
            log.warn("There is no VCS defined for workspace {}, skipping webhook deletion", workspace.getName());
            return;
        }

        Vcs vcs = workspace.getVcs();

        // Legacy mode: webhook owns its own remote hook
        if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
            switch (vcs.getVcsType()) {
                case GITHUB:
                    gitHubWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    break;
                case GITLAB:
                    gitLabWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    break;
                case BITBUCKET:
                    bitBucketWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    break;
                default:
                    break;
            }
            return;
        }

        // Shared mode: check if VcsWebhook should be cleaned up
        if (vcs.getVcsType() == io.terrakube.api.rs.vcs.VcsType.GITHUB) {
            cleanupSharedWebhookIfNeeded(workspace, vcs);
        }
    }

    private void cleanupSharedWebhookIfNeeded(Workspace workspace, Vcs vcs) {
        String normalizedUrl = WebhookServiceBase.normalizeRepositoryUrl(workspace.getSource());
        VcsWebhook vcsWebhook = vcsWebhookRepository.findByVcsAndRepositoryUrl(vcs, normalizedUrl)
                .orElse(null);

        if (vcsWebhook == null) {
            return;
        }

        // Count other workspaces still using this repo with webhooks (excluding the one being deleted)
        long remaining = workspaceRepository.findByVcsAndWebhookIsNotNull(vcs).stream()
                .filter(ws -> WebhookServiceBase.normalizeRepositoryUrl(ws.getSource()).equals(normalizedUrl))
                .filter(ws -> !ws.getId().equals(workspace.getId()))
                .count();

        if (remaining == 0) {
            // Last workspace — delete the shared GitHub hook and VcsWebhook record
            gitHubWebhookService.deleteSharedWebhook(vcs, workspace.getSource(), vcsWebhook.getRemoteHookId());
            vcsWebhookRepository.delete(vcsWebhook);
            log.info("Deleted shared VcsWebhook {} for repo {} (no remaining workspaces)", vcsWebhook.getId(), normalizedUrl);
        } else {
            // Other workspaces remain — recalculate event types
            List<String> remainingEvents = collectAllEventTypesForRepo(vcs, normalizedUrl, null);
            gitHubWebhookService.updateSharedWebhookEvents(vcs, workspace.getSource(), vcsWebhook.getRemoteHookId(), remainingEvents);
            log.info("Updated shared VcsWebhook {} events after removing workspace {}", vcsWebhook.getId(), workspace.getName());
        }
    }

    private boolean checkBranch(String webhookBranch, WebhookEvent webhookEvent) {
        String[] branchList = webhookEvent.getBranch().split(",");
        for (String branch : branchList) {
            branch = branch.trim();
            if (webhookBranch.matches(branch)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkFileChanges(List<String> files, WebhookEvent webhookEvent) {
        String[] triggeredPath = webhookEvent.getPath().split(",");
        for (String file : files) {
            for (int i = 0; i < triggeredPath.length; i++) {
                if (file.matches(triggeredPath[i])) {
                    log.info("Changed file {} matches set trigger pattern {}", file, triggeredPath[i]);
                    return true;
                }
            }
        }
        log.info("Changed files {} doesn't match any of the trigger path pattern {}", files, triggeredPath);
        return false;
    }

    private String findTemplateId(WebhookResult result, Webhook webhook) {
        return webhookEventRepository
                .findByWebhookAndEventOrderByPriorityAsc(webhook,
                        WebhookEventType.valueOf(result.getNormalizedEvent().toUpperCase()))
                .stream()
                .filter(webhookEvent -> checkBranch(result.getBranch(), webhookEvent)
                        && checkFileChanges(result.getFileChanges(), webhookEvent))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No valid template found for the configured webhook event " + result.getEvent() + "normalized " + result.getNormalizedEvent()))
                .getTemplateId();
    }

    private String findTemplateIdRelease(WebhookResult result, Webhook webhook) {
        return webhookEventRepository
                .findByWebhookAndEventOrderByPriorityAsc(webhook,
                        WebhookEventType.valueOf(result.getNormalizedEvent().toUpperCase()))
                .stream()
                .filter(webhookEvent -> checkBranch(result.getBranch(), webhookEvent))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No valid template found for the configured webhook event " + result.getEvent() + "normalized " + result.getNormalizedEvent()))
                .getTemplateId();
    }

    @Transactional
    public void processSharedWebhook(String vcsWebhookId, String jsonPayload, Map<String, String> headers) {
        VcsWebhook vcsWebhook = vcsWebhookRepository.findById(UUID.fromString(vcsWebhookId))
                .orElseThrow(() -> new IllegalArgumentException("VcsWebhook not found: " + vcsWebhookId));

        Vcs vcs = vcsWebhook.getVcs();
        WebhookResult webhookResult;

        switch (vcs.getVcsType()) {
            case GITHUB:
                webhookResult = gitHubWebhookService.processSharedWebhook(jsonPayload, headers, vcsWebhook.getSecret(), vcs);
                break;
            default:
                log.warn("Shared webhooks not supported for VCS type {}", vcs.getVcsType());
                return;
        }

        log.info("Shared webhook result {}", webhookResult);

        if (!webhookResult.isValid()
                || webhookResult.getEvent().equals(String.valueOf(WebhookEventType.PING).toLowerCase())) {
            return;
        }

        // Fan out to all workspaces on this repo
        String normalizedUrl = vcsWebhook.getRepositoryUrl();
        List<Workspace> workspaces = workspaceRepository.findByVcsAndWebhookIsNotNull(vcs).stream()
                .filter(ws -> WebhookServiceBase.normalizeRepositoryUrl(ws.getSource()).equals(normalizedUrl))
                .collect(Collectors.toList());

        log.info("Shared webhook fan-out: {} workspaces matched for repo {}", workspaces.size(), normalizedUrl);

        for (Workspace workspace : workspaces) {
            try {
                // Skip workspaces with legacy webhooks (they have their own GitHub hook)
                Webhook webhook = workspace.getWebhook();
                if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
                    log.debug("Skipping workspace {} — has legacy webhook", workspace.getName());
                    continue;
                }

                String templateId = webhookResult.isRelease()
                        ? findTemplateIdRelease(webhookResult, webhook)
                        : findTemplateId(webhookResult, webhook);

                log.info("Shared webhook: matched workspace {} with template {}", workspace.getName(), templateId);
                Job job = new Job();
                job.setTemplateReference(templateId);
                job.setRefresh(true);
                job.setPlanChanges(true);
                job.setRefreshOnly(false);
                job.setOverrideBranch(webhookResult.isRelease() ? "refs/tags/" + webhookResult.getBranch() : webhookResult.getBranch());
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
                if (!webhookResult.isRelease()) {
                    sendCommitStatus(savedJob);
                }
                scheduleJobService.createJobContext(savedJob);
            } catch (Exception e) {
                log.error("Error processing shared webhook for workspace {}", workspace.getName(), e);
            }
        }
    }

    @Transactional
    public void migrateToSharedWebhook(Webhook webhook) {
        Workspace workspace = webhook.getWorkspace();
        Vcs vcs = workspace.getVcs();

        if (vcs == null || vcs.getVcsType() != io.terrakube.api.rs.vcs.VcsType.GITHUB) {
            throw new IllegalArgumentException("Migration to shared webhook is only supported for GitHub");
        }

        if (webhook.getRemoteHookId() == null || webhook.getRemoteHookId().isEmpty()) {
            throw new IllegalArgumentException("Webhook is already using shared mode");
        }

        // Delete the old per-workspace GitHub hook
        gitHubWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());

        // Create or reuse the shared VcsWebhook
        String normalizedUrl = WebhookServiceBase.normalizeRepositoryUrl(workspace.getSource());
        VcsWebhook vcsWebhook = vcsWebhookRepository.findByVcsAndRepositoryUrl(vcs, normalizedUrl)
                .orElse(null);

        if (vcsWebhook == null) {
            vcsWebhook = new VcsWebhook();
            vcsWebhook.setVcs(vcs);
            vcsWebhook.setRepositoryUrl(normalizedUrl);
            vcsWebhook.setSecret(UUID.randomUUID().toString());
            vcsWebhook = vcsWebhookRepository.save(vcsWebhook);

            String callbackUrl = String.format("https://%s/webhook/v1/vcs/%s", hostname, vcsWebhook.getId().toString());
            List<String> events = collectAllEventTypesForRepo(vcs, normalizedUrl, webhook);
            String remoteHookId = gitHubWebhookService.createSharedWebhook(vcs, workspace.getSource(), callbackUrl, vcsWebhook.getSecret(), events);
            if (remoteHookId == null) {
                vcsWebhookRepository.delete(vcsWebhook);
                throw new IllegalArgumentException("Error creating shared GitHub webhook during migration");
            }
            vcsWebhook.setRemoteHookId(remoteHookId);
            vcsWebhookRepository.save(vcsWebhook);
        } else {
            // Update event types on the existing shared hook
            List<String> allEvents = collectAllEventTypesForRepo(vcs, normalizedUrl, webhook);
            gitHubWebhookService.updateSharedWebhookEvents(vcs, workspace.getSource(), vcsWebhook.getRemoteHookId(), allEvents);
        }

        // Clear the per-workspace remote hook ID to mark as shared mode
        webhook.setRemoteHookId(null);
        webhookRepository.save(webhook);

        log.info("Migrated workspace {} webhook to shared VcsWebhook {}", workspace.getName(), vcsWebhook.getId());
    }

    private void sendCommitStatus(Job job) {
        switch (job.getWorkspace().getVcs().getVcsType()) {
            case GITHUB:
                gitHubWebhookService.sendCommitStatus(job, JobStatus.pending);
                break;
            case GITLAB:
                gitLabWebhookService.sendCommitStatus(job, JobStatus.pending);
                break;
            default:
                break;
        }
    }
}
