package io.terrakube.api.plugin.vcs;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.quartz.SchedulerException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
@Service
public class RepoWebhookService {

    RepoWebhookRepository repoWebhookRepository;
    WorkspaceRepository workspaceRepository;
    WebhookEventRepository webhookEventRepository;
    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;
    PrCommentService prCommentService;

    private boolean isGitLab(RepoWebhook repoWebhook) {
        return repoWebhook.getVcs() != null && repoWebhook.getVcs().getVcsType() == VcsType.GITLAB;
    }

    private boolean isGitLab(Workspace workspace) {
        return workspace.getVcs() != null && workspace.getVcs().getVcsType() == VcsType.GITLAB;
    }

    // Callers reach this exclusively through RepoWebhookSyncJob, which is
    // @DisallowConcurrentExecution and keyed by a hash of the normalized
    // repository URL — Quartz (cluster mode, JDBC job store) guarantees at
    // most one execution of that job per URL, cluster-wide, so at most one
    // caller ever reaches this find-or-create for a given URL at a time.
    // The catch below is a defense-in-depth fallback (e.g. if this is ever
    // called from somewhere outside that serialized path), not the primary
    // safety mechanism.
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
                        // saveAndFlush forces the INSERT to happen here,
                        // inside this try block, instead of being silently
                        // queued until the transaction commits — a plain
                        // save() would let a real conflict surface long
                        // after this catch block is out of scope.
                        return repoWebhookRepository.saveAndFlush(repoWebhook);
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
        boolean hasPrWorkflow = false;
        for (Workspace ws : workspaces) {
            if (ws.getWebhook() != null && ws.getWebhook().getEvents() != null) {
                for (WebhookEvent event : ws.getWebhook().getEvents()) {
                    eventTypes.add(event.getEvent());
                    hasPrWorkflow = hasPrWorkflow || event.isPrWorkflowEnabled();
                }
            }
        }

        if (eventTypes.isEmpty()) {
            log.warn("No webhook event types found for repo webhook {}", repoWebhook.getId());
            return;
        }

        String remoteHookId = isGitLab(repoWebhook)
                ? gitLabWebhookService.createOrUpdateRepoWebhook(repoWebhook, eventTypes, hasPrWorkflow)
                : gitHubWebhookService.createOrUpdateRepoWebhook(repoWebhook, eventTypes, hasPrWorkflow);
        repoWebhook.setRemoteHookId(remoteHookId);
        repoWebhookRepository.save(repoWebhook);
    }

    @Transactional
    public void cleanupIfOrphan(RepoWebhook repoWebhook) {
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(repoWebhook.getRepositoryUrl());

        if (workspaces.isEmpty()) {
            if (isGitLab(repoWebhook)) {
                gitLabWebhookService.deleteRepoWebhook(repoWebhook);
            } else {
                gitHubWebhookService.deleteRepoWebhook(repoWebhook);
            }
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

        boolean gitlab = isGitLab(repoWebhook);
        if (gitlab) {
            if (!verifyGitlabToken(headers, repoWebhook.getWebhookSecret())) {
                log.error("Token verification failed for repo webhook {}", repoWebhookId);
                throw new SecurityException("GitLab token verification failed");
            }
        } else if (!verifyHmacSignature(headers, repoWebhook.getWebhookSecret(), jsonPayload)) {
            log.error("Signature verification failed for repo webhook {}", repoWebhookId);
            throw new SecurityException("HMAC signature verification failed");
        }

        WebhookResult webhookResult = gitlab
                ? gitLabWebhookService.parseGitLabPayload(jsonPayload, headers)
                : gitHubWebhookService.parseGitHubPayload(jsonPayload, headers);

        if (webhookResult.getEvent() != null && webhookResult.getEvent().equals("ping")) {
            log.info("Received ping for repo webhook {}", repoWebhookId);
            return;
        }

        if (!webhookResult.isValid()) {
            log.warn("Invalid webhook result for repo webhook {}", repoWebhookId);
            return;
        }

        String normalizedUrl = repoWebhook.getRepositoryUrl();
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(normalizedUrl);

        log.info("Processing v2 webhook for {} workspaces on repo {}", workspaces.size(), normalizedUrl);

        for (Workspace workspace : workspaces) {
            try {
                processWorkspaceWebhook(workspace, webhookResult);
            } catch (Exception e) {
                log.error("Error processing v2 webhook for workspace {}: {}", workspace.getName(), e.getMessage(), e);
            }
        }
    }

    private void processWorkspaceWebhook(Workspace workspace, WebhookResult webhookResult) {
        if (workspace.getWebhook() == null) {
            log.warn("Workspace {} has no webhook despite being returned by migrated query", workspace.getName());
            return;
        }

        if (webhookResult.getPrDetailsUrl() != null && webhookResult.getCommit() == null) {
            if (workspace.getVcs() == null) {
                log.warn("Workspace {} has no VCS, cannot resolve PR comment details", workspace.getName());
                return;
            }
            boolean resolved = gitHubWebhookService.resolvePrDetails(workspace.getVcs(), workspace.getSource(),
                    webhookResult.getPrDetailsUrl(), webhookResult);
            if (!resolved) {
                log.warn("Failed to resolve PR comment details for workspace {}, skipping", workspace.getName());
                return;
            }
        }

        if (webhookResult.getPrFilesUrl() != null) {
            if (workspace.getVcs() != null) {
                List<String> prFiles = isGitLab(workspace)
                        ? gitLabWebhookService.fetchPrFileChanges(
                                workspace.getVcs(), workspace.getSource(), webhookResult.getPrFilesUrl())
                        : gitHubWebhookService.fetchPrFileChanges(
                                workspace.getVcs(), workspace.getSource(), webhookResult.getPrFilesUrl());
                webhookResult.setFileChanges(prFiles);
            } else {
                log.warn("Workspace {} has no VCS, cannot fetch PR file changes", workspace.getName());
                return;
            }
        }

        if (webhookResult.isPrComment()) {
            prCommentService.acknowledgeReceipt(workspace, webhookResult.getCommentId(), webhookResult.getPrNumber());
        }

        try {
            // Release events have no PR-workflow concept; everything else (push, pull_request,
            // and PR comment commands) is matched via findMatchingEvent so isPrWorkflowEnabled()/
            // isPrApplyEnabled() are available below - without those, a PR-triggered job never
            // gets prNumber set and PrCommentService.postPlanResult()/postApplyResult() silently
            // no-op (they bail out immediately when job.getPrNumber() is null/0), so "Post Plan
            // on PR" would never actually post a comment for a repo on the shared v2 webhook.
            WebhookEvent matchedEvent = webhookResult.isRelease() ? null
                    : WebhookEventMatcher.findMatchingEvent(webhookResult, workspace.getWebhook(),
                            webhookEventRepository);

            // Mirrors WebhookService.handlePrCommentCommand: a "terrakube plan"/"terrakube apply"
            // comment only starts a job when PR workflow is actually enabled on the matched event.
            if (webhookResult.isPrComment()) {
                if (!matchedEvent.isPrWorkflowEnabled()) {
                    log.info("Ignoring PR {} comment for workspace {}: PR workflow is not enabled",
                            webhookResult.getCommentCommand(), workspace.getName());
                    return;
                }
                if ("apply".equals(webhookResult.getCommentCommand())) {
                    createPrApplyJob(workspace, webhookResult, matchedEvent);
                    return;
                }
            }

            String templateId = webhookResult.isRelease()
                    ? WebhookEventMatcher.findTemplateIdRelease(webhookResult, workspace.getWebhook(),
                            webhookEventRepository)
                    : matchedEvent.getTemplateId();

            log.info("V2 webhook event {} for workspace {}, using template {}", webhookResult.getNormalizedEvent(),
                    workspace.getName(), templateId);

            Job job = buildJob(workspace, webhookResult, templateId);
            if (matchedEvent != null && matchedEvent.isPrWorkflowEnabled() && webhookResult.getPrNumber() != null) {
                job.setPrNumber(webhookResult.getPrNumber().intValue());
                job.setPrApplyEnabled(matchedEvent.isPrApplyEnabled());
            }
            if (webhookResult.isPrComment()) {
                job.setCommandCommentId(webhookResult.getCommentId());
            }
            saveAndScheduleJob(workspace, webhookResult, job);
        } catch (IllegalArgumentException e) {
            log.info("No matching template for workspace {} on event {}: {}", workspace.getName(),
                    webhookResult.getNormalizedEvent(), e.getMessage());
        } catch (Exception e) {
            log.error("Error creating job for workspace {}", workspace.getName(), e);
        }
    }

    /**
     * Mirrors WebhookService.handlePrCommentCommand's "apply" branch: unlike a "terrakube plan"
     * comment (which reuses the PR's regular template), apply always runs the workspace's default
     * template with autoApply=true, behind the same PR-apply workspace lock so ScheduleJob lets
     * only this job through (see ScheduleJob.isOwnPrApplyLock) and unlocks it on completion.
     */
    private void createPrApplyJob(Workspace workspace, WebhookResult webhookResult, WebhookEvent matchedEvent)
            throws ParseException, SchedulerException {
        Number prNumber = webhookResult.getPrNumber();
        if (!matchedEvent.isPrApplyEnabled()) {
            log.info("Rejecting PR apply comment for workspace {}: apply via PR comment is not enabled", workspace.getName());
            prCommentService.postApplyDisabledNotice(workspace, prNumber != null ? prNumber.intValue() : null);
            return;
        }

        String templateId = workspace.getDefaultTemplate();
        if (templateId == null || templateId.isEmpty()) {
            log.error("No default template configured for apply in PR workflow on workspace {}", workspace.getName());
            return;
        }

        log.info("PR comment apply for workspace {}, using default template {}", workspace.getName(), templateId);
        workspace.setLocked(true);
        workspace.setLockDescription(WebhookService.buildPrApplyLockDescription(prNumber != null ? prNumber.intValue() : null));
        workspaceRepository.save(workspace);

        Job job = buildJob(workspace, webhookResult, templateId);
        job.setPrNumber(prNumber != null ? prNumber.intValue() : null);
        job.setAutoApply(true);
        job.setCommandCommentId(webhookResult.getCommentId());
        saveAndScheduleJob(workspace, webhookResult, job);
    }

    private Job buildJob(Workspace workspace, WebhookResult webhookResult, String templateId) {
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
        return job;
    }

    private void saveAndScheduleJob(Workspace workspace, WebhookResult webhookResult, Job job)
            throws ParseException, SchedulerException {
        Job savedJob = jobRepository.save(job);
        if (!webhookResult.isRelease() && workspace.getVcs() != null) {
            if (isGitLab(workspace)) {
                gitLabWebhookService.sendCommitStatus(savedJob, JobStatus.pending, null);
            } else {
                gitHubWebhookService.sendCommitStatus(savedJob, JobStatus.pending, null);
            }
        }
        scheduleJobService.createJobContext(savedJob);
    }

    private boolean verifyGitlabToken(Map<String, String> headers, String secret) {
        String tokenHeader = headers.get("x-gitlab-token");
        if (tokenHeader == null) {
            log.error("x-gitlab-token header is missing!");
            return false;
        }
        return MessageDigest.isEqual(
                tokenHeader.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }

    private boolean verifyHmacSignature(Map<String, String> headers, String secret, String payload) {
        try {
            String signatureHeader = headers.get("x-hub-signature-256");
            if (signatureHeader == null) {
                log.error("x-hub-signature-256 header is missing!");
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] computedHash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * computedHash.length);
            for (byte b : computedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            String expectedSignature = "sha256=" + hexString.toString();
            if (!MessageDigest.isEqual(
                    signatureHeader.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8))) {
                log.error("Request signature didn't match!");
                return false;
            }
            return true;
        } catch (NoSuchAlgorithmException e) {
            log.error("Error processing the webhook", e);
            return false;
        } catch (InvalidKeyException e) {
            log.error("Error parsing the secret", e);
            return false;
        }
    }
}
