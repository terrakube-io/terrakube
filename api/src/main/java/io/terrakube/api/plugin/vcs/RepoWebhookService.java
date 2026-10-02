package io.terrakube.api.plugin.vcs;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.quartz.SchedulerException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    AzDevOpsWebhookService azDevOpsWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;
    PrCommentService prCommentService;
    RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;
    ObjectMapper objectMapper;
    Executor workspaceFanoutExecutor;
    VcsRepository vcsRepository;

    // Events the GitHub App endpoint enqueues; everything else (ping, installation, ...) is acknowledged and dropped.
    private static final Set<String> GITHUB_APP_EVENTS = Set.of("push", "pull_request", "issue_comment", "release");

    // A GitHub App VCS that receives events through the App's own webhook: such workspaces never get
    // per-repository hooks, and repo-hook (v2) deliveries skip them so an event never fires twice.
    // Without a secret nothing can be verified, so the VCS stays on repository hooks.
    public static boolean isAppWebhookMode(Vcs vcs) {
        return vcs != null && vcs.getVcsType() == VcsType.GITHUB
                && vcs.getConnectionType() == VcsConnectionType.STANDALONE && vcs.isAppWebhookEnabled()
                && vcs.getWebhookSecret() != null && !vcs.getWebhookSecret().isBlank();
    }

    // GitHub, GitLab and Azure DevOps (AZURE_SP_MI / AZURE_DEVOPS) participate in the shared,
    // repository-level (v2) webhook flow reconciled asynchronously by RepoWebhookSyncJob.
    public static boolean isSharedWebhookProvider(Vcs vcs) {
        VcsType vcsType = vcs != null ? vcs.getVcsType() : null;
        return vcsType == VcsType.GITHUB || vcsType == VcsType.GITLAB || vcsType == VcsType.AZURE_SP_MI
                || vcsType == VcsType.AZURE_DEVOPS;
    }

    private boolean isGitLab(RepoWebhook repoWebhook) {
        return repoWebhook.getVcs() != null && repoWebhook.getVcs().getVcsType() == VcsType.GITLAB;
    }

    private boolean isGitLab(Workspace workspace) {
        return workspace.getVcs() != null && workspace.getVcs().getVcsType() == VcsType.GITLAB;
    }

    private boolean isAzureDevOps(RepoWebhook repoWebhook) {
        return repoWebhook.getVcs() != null
                && (repoWebhook.getVcs().getVcsType() == VcsType.AZURE_SP_MI
                        || repoWebhook.getVcs().getVcsType() == VcsType.AZURE_DEVOPS);
    }

    private boolean isAzureDevOps(Workspace workspace) {
        return workspace.getVcs() != null
                && (workspace.getVcs().getVcsType() == VcsType.AZURE_SP_MI
                        || workspace.getVcs().getVcsType() == VcsType.AZURE_DEVOPS);
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
        List<Workspace> repoHookWorkspaces = workspaces.stream()
                .filter(ws -> !isAppWebhookMode(ws.getVcs()))
                .toList();

        // Every workspace on this repo gets its events through a GitHub App webhook: the repo-level
        // hook is no longer needed. The RepoWebhook row stays, the App endpoint looks it up by URL.
        if (!workspaces.isEmpty() && repoHookWorkspaces.isEmpty()) {
            if (repoWebhook.getRemoteHookId() != null && !repoWebhook.getRemoteHookId().isEmpty()) {
                try {
                    if (!gitHubWebhookService.deleteRepoWebhook(repoWebhook)) {
                        throw new IllegalStateException("Could not delete repo webhook " + repoWebhook.getId()
                                + " for " + repoWebhook.getRepositoryUrl() + ", keeping its remote hook id");
                    }
                } catch (HttpClientErrorException e) {
                    // 404: the hook is already gone. 403: the App may lack "Webhooks" permission and the hook
                    // still exists, so keep its id for a VCS that can update or delete it later; meanwhile its
                    // deliveries skip App-mode workspaces. Anything else is rethrown so the sync job retries.
                    int status = e.getStatusCode().value();
                    if (status == HttpStatus.FORBIDDEN.value()) {
                        log.warn("Could not delete repo webhook {} for {} (403), keeping its remote hook id",
                                repoWebhook.getId(), repoWebhook.getRepositoryUrl());
                        return;
                    }
                    if (status != HttpStatus.NOT_FOUND.value()) {
                        throw e;
                    }
                }
                repoWebhook.setRemoteHookId(null);
                repoWebhookRepository.save(repoWebhook);
            }
            return;
        }

        // The row may have been seeded by an App-mode VCS, whose credentials may not manage
        // repository hooks: the repo hook is for the repo-hook workspaces, so use one of theirs.
        if (isAppWebhookMode(repoWebhook.getVcs())) {
            repoHookWorkspaces.stream().map(Workspace::getVcs).filter(Objects::nonNull).findFirst()
                    .ifPresent(repoWebhook::setVcs);
        }

        Set<WebhookEventType> eventTypes = new HashSet<>();
        boolean hasPrWorkflow = false;
        for (Workspace ws : repoHookWorkspaces) {
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

        String remoteHookId;
        if (isAzureDevOps(repoWebhook)) {
            remoteHookId = azDevOpsWebhookService.createOrUpdateRepoWebhook(repoWebhook, eventTypes);
        } else if (isGitLab(repoWebhook)) {
            remoteHookId = gitLabWebhookService.createOrUpdateRepoWebhook(repoWebhook, eventTypes, hasPrWorkflow);
        } else {
            remoteHookId = gitHubWebhookService.createOrUpdateRepoWebhook(repoWebhook, eventTypes, hasPrWorkflow);
        }
        repoWebhook.setRemoteHookId(remoteHookId);
        repoWebhookRepository.save(repoWebhook);
    }

    @Transactional
    public void cleanupIfOrphan(RepoWebhook repoWebhook) {
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(repoWebhook.getRepositoryUrl());

        if (workspaces.isEmpty()) {
            if (isAzureDevOps(repoWebhook)) {
                azDevOpsWebhookService.deleteRepoWebhook(repoWebhook);
            } else if (isGitLab(repoWebhook)) {
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

    // @Transactional so repoWebhook.getVcs() (lazily loaded, accessed below by isGitLab/isAzureDevOps
    // for every provider) has an open session to load through - without it, findById()'s own
    // implicit per-call transaction closes before this method body runs, leaving vcs an
    // uninitialized proxy and throwing LazyInitializationException on every delivery. This is safe
    // for the immediate-dispatch ordering: a Spring transactional proxy commits the transaction
    // (including repoWebhookDeliveryTransactions.enqueue()'s nested, REQUIRED-propagation insert)
    // before returning control to the caller, so by the time WebHookController receives the
    // deliveryId and calls dispatchAsync, the enqueue is already committed and visible.
    @Transactional
    public UUID acceptV2Webhook(String repoWebhookId, String jsonPayload, Map<String, String> headers) {
        // HTTP header names are case-insensitive; downstream verification looks them up in lowercase.
        headers = WebhookHeaders.caseInsensitive(headers);
        RepoWebhook repoWebhook = repoWebhookRepository.findById(UUID.fromString(repoWebhookId))
                .orElseThrow(() -> new IllegalArgumentException("Repo webhook not found: " + repoWebhookId));

        boolean gitlab = isGitLab(repoWebhook);
        boolean azureDevOps = isAzureDevOps(repoWebhook);
        if (azureDevOps) {
            if (!verifyAzDevOpsToken(headers, repoWebhook.getWebhookSecret())) {
                log.error("Token verification failed for repo webhook {}", repoWebhookId);
                throw new SecurityException("Azure DevOps token verification failed");
            }
        } else if (gitlab) {
            if (!verifyGitlabToken(headers, repoWebhook.getWebhookSecret())) {
                log.error("Token verification failed for repo webhook {}", repoWebhookId);
                throw new SecurityException("GitLab token verification failed");
            }
        } else if (!verifyHmacSignature(headers, repoWebhook.getWebhookSecret(), jsonPayload)) {
            log.error("Signature verification failed for repo webhook {}", repoWebhookId);
            throw new SecurityException("HMAC signature verification failed");
        }

        return repoWebhookDeliveryTransactions.enqueue(repoWebhook, jsonPayload, serializeHeaders(headers));
    }

    // The single webhook of a GitHub App, signed with Vcs.webhookSecret over the raw request body.
    // Returns the enqueued delivery id, or null when the event was accepted but there is nothing to
    // dispatch (ignored event type, unknown repository, already received).
    // Not @Transactional: nothing below reads a lazy association, and enqueue() must commit or fail
    // on its own so a repeated dedupe key can be caught here instead of rolling back a caller.
    public UUID acceptGitHubAppWebhook(String vcsId, byte[] rawBody, Map<String, String> headers)
            throws GeneralSecurityException {
        headers = WebhookHeaders.caseInsensitive(headers);
        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId))
                .orElseThrow(() -> new IllegalArgumentException("VCS not found: " + vcsId));
        if (!isAppWebhookMode(vcs)) {
            throw new SecurityException("GitHub App webhook is not enabled for VCS " + vcsId);
        }
        String signature = headers.get("x-hub-signature-256");
        if (signature == null || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                hmacSha256Signature(vcs.getWebhookSecret(), rawBody).getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("HMAC signature verification failed for VCS " + vcsId);
        }
        String rawPayload = new String(rawBody, StandardCharsets.UTF_8);

        String event = headers.get("x-github-event");
        if (event == null || event.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing X-GitHub-Event header");
        }
        if (!GITHUB_APP_EVENTS.contains(event)) {
            log.info("Ignoring GitHub App event {} for VCS {}", event, vcsId);
            return null;
        }

        JsonNode repository;
        try {
            repository = objectMapper.readTree(rawPayload).path("repository");
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed GitHub App webhook payload");
        }
        String normalizedUrl = RepoUrlNormalizer.normalize(
                repository.path("clone_url").asText(repository.path("html_url").asText(null)));
        RepoWebhook repoWebhook = normalizedUrl == null ? null
                : repoWebhookRepository.findByRepositoryUrl(normalizedUrl).orElse(null);
        if (repoWebhook == null) {
            // Rows are created by RepoWebhookSyncJob for repos with migrated workspaces; never here.
            log.info("No repo webhook for {}, dropping GitHub App {} event", normalizedUrl, event);
            return null;
        }

        // Keyed on the verified signature, not X-GitHub-Delivery: it binds to the body, so a replay
        // under a fresh delivery guid is dropped like a GitHub redelivery.
        try {
            return repoWebhookDeliveryTransactions.enqueue(repoWebhook, rawPayload, serializeHeaders(headers), vcs,
                    signature);
        } catch (DataIntegrityViolationException e) {
            log.info("GitHub App delivery {} for VCS {} not enqueued: already received, or its VCS or repo "
                    + "webhook is gone", headers.get("x-github-delivery"), vcsId);
            return null;
        }
    }

    private String serializeHeaders(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook headers", e);
        }
    }

    // deliveryVcs is the VCS whose GitHub App webhook received this delivery, or null for a
    // repo-hook (v2) delivery.
    public void processClaimedDelivery(RepoWebhook repoWebhook, Vcs deliveryVcs, String jsonPayload,
            Map<String, String> headers) {
        headers = WebhookHeaders.caseInsensitive(headers);
        // A GitHub App delivery is always a GitHub payload, whatever VCS the RepoWebhook row was seeded with.
        boolean azureDevOps = deliveryVcs == null && isAzureDevOps(repoWebhook);
        boolean gitlab = deliveryVcs == null && isGitLab(repoWebhook);

        WebhookResult webhookResult;
        if (azureDevOps) {
            webhookResult = azDevOpsWebhookService.parseAzDevOpsPayload(jsonPayload, headers);
        } else if (gitlab) {
            webhookResult = gitLabWebhookService.parseGitLabPayload(jsonPayload, headers);
        } else {
            webhookResult = gitHubWebhookService.parseGitHubPayload(jsonPayload, headers);
        }

        if (webhookResult.getEvent() != null && webhookResult.getEvent().equals("ping")) {
            log.info("Received ping for repo webhook {}", repoWebhook.getId());
            return;
        }

        if (!webhookResult.isValid()) {
            log.warn("Invalid webhook result for repo webhook {}", repoWebhook.getId());
            return;
        }

        String normalizedUrl = repoWebhook.getRepositoryUrl();

        // The App secret is shared by every installation of the App, so a payload URL is never trusted
        // with a token: rebuild the PR URLs from the VCS API URL and the matched repository.
        // Without one (not a plain owner/repo URL) the PR files are not fetched, and a PR comment, whose
        // head commit cannot then be resolved, is dropped.
        if (deliveryVcs != null && webhookResult.getPrFilesUrl() != null) {
            String prUrl = gitHubWebhookService.pullRequestApiUrl(deliveryVcs, normalizedUrl,
                    webhookResult.getPrNumber().intValue());
            if (prUrl == null && webhookResult.isPrComment()) {
                return;
            }
            webhookResult.setPrFilesUrl(prUrl == null ? null : prUrl + "/files");
            if (webhookResult.getPrDetailsUrl() != null) {
                webhookResult.setPrDetailsUrl(prUrl);
            }
        }

        // GitHub/GitLab PR file changes are a repo-level fact (which files a PR touched doesn't
        // depend on whose credentials asked), but processWorkspaceWebhook used to fetch them fresh
        // per workspace - on a shared webhook with N workspaces, that's N redundant paginated API
        // calls for the exact same answer. Fetch once here with the repo webhook's own Vcs and let
        // every workspace below reuse it; processWorkspaceWebhook still fetches per-workspace as a
        // fallback if this didn't populate anything (no repo-level Vcs, or the fetch came back
        // empty - which also covers a repo-level credential that can't read this PR, so a workspace
        // with its own working credentials still gets a chance). Azure DevOps is deliberately
        // excluded: per-workspace credentials there aren't just a fallback, see the comment in
        // processWorkspaceWebhook.
        // A GitHub App delivery prefetches with the App's own installation token.
        Vcs prefetchVcs = deliveryVcs != null ? deliveryVcs : repoWebhook.getVcs();
        if (!azureDevOps && webhookResult.getPrFilesUrl() != null && prefetchVcs != null) {
            List<String> prFiles = gitlab
                    ? gitLabWebhookService.fetchPrFileChanges(prefetchVcs, normalizedUrl,
                            webhookResult.getPrFilesUrl())
                    : gitHubWebhookService.fetchPrFileChanges(prefetchVcs, normalizedUrl,
                            webhookResult.getPrFilesUrl());
            webhookResult.setFileChanges(prFiles);
        }

        Set<UUID> appVcsIds = deliveryVcs != null ? appWebhookVcsIds(deliveryVcs) : null;
        List<Workspace> workspaces = workspaceRepository
                .findByNormalizedSourceWithMigratedWebhook(normalizedUrl).stream()
                .filter(ws -> appVcsIds != null
                        ? ws.getVcs() != null && appVcsIds.contains(ws.getVcs().getId())
                        : !isAppWebhookMode(ws.getVcs()))
                .toList();

        log.info("Processing v2 webhook for {} workspaces on repo {}", workspaces.size(), normalizedUrl);

        // Bounded concurrency (workspaceFanoutExecutor, default 4 - see WorkspaceFanoutExecutorConfig)
        // instead of a fully serial loop: a shared webhook with 35+ workspaces no longer processes
        // them one at a time, but also can't open unbounded concurrent DB/VCS/executor connections.
        // join() blocks until every workspace has been attempted (successfully or not - each
        // failure is caught and logged individually below, same as before), so the caller
        // (RepoWebhookDispatchService.attemptDelivery) only records this delivery PROCESSED once
        // every workspace item has actually been accepted.
        List<CompletableFuture<Void>> tasks = workspaces.stream()
                .map(workspace -> CompletableFuture.runAsync(() -> {
                    try {
                        processWorkspaceWebhook(workspace, webhookResult);
                    } catch (Exception e) {
                        log.error("Error processing v2 webhook for workspace {}: {}", workspace.getName(),
                                e.getMessage(), e);
                    }
                }, workspaceFanoutExecutor))
                .toList();
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }

    // The VCS rows a GitHub App delivery may reach: the same App (clientId holds the App ID) on the
    // same GitHub host, in App webhook mode, with the same secret - i.e. every row whose secret
    // would have verified this signature. Several organizations can register the same App.
    // deliveryVcs is in App webhook mode (checked when the delivery was claimed), so its secret is set.
    private Set<UUID> appWebhookVcsIds(Vcs deliveryVcs) {
        if (deliveryVcs.getClientId() == null) {
            return Set.of(deliveryVcs.getId());
        }
        String apiUrl = GitHubWebhookService.normalizeApiUrl(deliveryVcs.getApiUrl());
        byte[] secret = deliveryVcs.getWebhookSecret().getBytes(StandardCharsets.UTF_8);
        Set<UUID> ids = new HashSet<>();
        for (Vcs vcs : vcsRepository.findByClientId(deliveryVcs.getClientId())) {
            // isAppWebhookMode also skips rows with a blank secret.
            if (isAppWebhookMode(vcs) && apiUrl.equals(GitHubWebhookService.normalizeApiUrl(vcs.getApiUrl()))
                    && MessageDigest.isEqual(secret, vcs.getWebhookSecret().getBytes(StandardCharsets.UTF_8))) {
                ids.add(vcs.getId());
            }
        }
        return ids;
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

        // Azure DevOps: file changes require per-workspace API calls (push payloads
        // don't include changed files, and PR changes need workspace VCS credentials).
        if (isAzureDevOps(workspace) && workspace.getVcs() != null) {
            String normalizedEvent = webhookResult.getNormalizedEvent();
            if ("push".equals(normalizedEvent) && !webhookResult.isRelease() && webhookResult.getRawPayload() != null) {
                webhookResult.setFileChanges(
                        azDevOpsWebhookService.fetchPushFileChanges(
                                workspace.getVcs(), workspace.getSource(), webhookResult.getRawPayload()));
            } else if ("pull_request".equals(normalizedEvent) && webhookResult.getPrNumber() != null) {
                webhookResult.setFileChanges(
                        azDevOpsWebhookService.fetchPrFileChanges(
                                workspace.getVcs(), workspace.getSource(), webhookResult.getPrNumber().intValue()));
            }
        } else if (webhookResult.getPrFilesUrl() != null
                && (webhookResult.getFileChanges() == null || webhookResult.getFileChanges().isEmpty())) {
            // Fallback only: processClaimedDelivery already tried this once, repo-wide, with the
            // repo webhook's own Vcs. Reaching here means that either didn't run (no repo-level
            // Vcs) or came back empty - which could genuinely be a zero-file-change PR, or could be
            // the repo-level credential lacking access while this workspace's own credential works,
            // so it's still worth this workspace trying with its own Vcs.
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
        job.setVia(webhookResult.getVia() != null ? webhookResult.getVia() : JobVia.UI.getValue());
        job.setCommitId(webhookResult.getCommit());
        return job;
    }

    private void saveAndScheduleJob(Workspace workspace, WebhookResult webhookResult, Job job)
            throws ParseException, SchedulerException {
        Job savedJob = jobRepository.save(job);
        if (!webhookResult.isRelease() && workspace.getVcs() != null) {
            if (isAzureDevOps(workspace)) {
                azDevOpsWebhookService.sendCommitStatus(savedJob, JobStatus.pending, null);
            } else if (isGitLab(workspace)) {
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
            String expectedSignature = hmacSha256Signature(secret, payload.getBytes(StandardCharsets.UTF_8));
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

    private static String hmacSha256Signature(String secret, byte[] payload)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private boolean verifyAzDevOpsToken(Map<String, String> headers, String secret) {
        String tokenHeader = headers.get("x-terrakube-token");
        if (tokenHeader == null) {
            log.error("x-terrakube-token header is missing!");
            return false;
        }
        return MessageDigest.isEqual(
                tokenHeader.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }
}
