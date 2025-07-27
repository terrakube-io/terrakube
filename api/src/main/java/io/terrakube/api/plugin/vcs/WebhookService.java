package io.terrakube.api.plugin.vcs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
@Service
public class WebhookService {

    WebhookRepository webhookRepository;
    WebhookEventRepository webhookEventRepository;
    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    BitBucketWebhookService bitBucketWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;
    ObjectMapper objectMapper;

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

        String webhookRemoteId = "";

        Vcs vcs = workspace.getVcs();
        switch (vcs.getVcsType()) {
            case GITHUB:
                webhookRemoteId = gitHubWebhookService.createOrUpdateWebhook(workspace, webhook);
                break;
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

    @Transactional
    public void deleteWorkspaceWebhook(Webhook webhook) {
        Workspace workspace = webhook.getWorkspace();
        if (workspace.getVcs() == null) {
            log.warn("There is no VCS defined for workspace {}, skipping webhook creation", workspace.getName());
            return;
        }
        if (webhook.getRemoteHookId() == null || webhook.getRemoteHookId().isEmpty()) {
            log.warn("No remote hook id found for webhook {} on workspace {}, skipping webhook deletion",
                    webhook.getId(), workspace.getName());
            return;
        }

        Vcs vcs = workspace.getVcs();
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
                        "No valid template found for the configured webhook event " + result.getNormalizedEvent()))
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
                        "No valid template found for the configured webhook event " + result.getNormalizedEvent()))
                .getTemplateId();
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
