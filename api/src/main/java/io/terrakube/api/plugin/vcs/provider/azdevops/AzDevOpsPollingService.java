package io.terrakube.api.plugin.vcs.provider.azdevops;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.extern.slf4j.Slf4j;

/**
 * Polls Azure DevOps for new commits and triggers a plan, as an alternative to inbound service hook
 * webhooks. Azure DevOps Services (dev.azure.com) delivers service hooks from the public cloud, which
 * cannot reach a Terrakube running on a private network. Polling reverses the direction: Terrakube
 * makes outbound calls (which already work for token/clone) to detect new commits.
 *
 * <p>For every workspace backed by an Azure DevOps VCS that has a webhook configured, the tip commit
 * of the workspace branch is fetched and compared against the previously seen commit (kept in Redis).
 * When it changes, the same event-matching and job-scheduling used by inbound webhooks runs.
 *
 * <p>Disabled by default; enable with {@code io.terrakube.vcs.azuredevops.polling.enabled=true}.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "io.terrakube.vcs.azuredevops.polling.enabled", havingValue = "true")
public class AzDevOpsPollingService {

    private static final String KEY_PREFIX = "azdo-poll:";
    private static final List<VcsType> AZURE_TYPES = List.of(VcsType.AZURE_DEVOPS, VcsType.AZURE_SP_MI);
    private static final int MAX_POLL_THREADS = 8;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final WebhookRepository webhookRepository;
    private final AzDevOpsWebhookService azDevOpsWebhookService;
    private final WebhookService webhookService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ExecutorService pollExecutor;

    public AzDevOpsPollingService(WebhookRepository webhookRepository,
            AzDevOpsWebhookService azDevOpsWebhookService, WebhookService webhookService,
            RedisTemplate<String, Object> redisTemplate) {
        this.webhookRepository = webhookRepository;
        this.azDevOpsWebhookService = azDevOpsWebhookService;
        this.webhookService = webhookService;
        this.redisTemplate = redisTemplate;
        this.pollExecutor = Executors.newFixedThreadPool(MAX_POLL_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "azdo-poll-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        log.info("Azure DevOps commit polling is enabled");
    }

    @PreDestroy
    public void shutdown() {
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelayString = "${io.terrakube.vcs.azuredevops.polling.interval:15000}",
            initialDelayString = "${io.terrakube.vcs.azuredevops.polling.initial-delay:10000}")
    public void poll() {
        List<Webhook> webhooks;
        try {
            webhooks = webhookRepository.findByWorkspaceVcsTypeIn(AZURE_TYPES);
        } catch (Exception e) {
            log.error("Azure DevOps polling: failed to load webhooks", e);
            return;
        }

        if (webhooks.isEmpty()) {
            return;
        }

        // Poll every workspace concurrently (bounded by MAX_POLL_THREADS) so the time to detect a new
        // commit stays close to the configured interval regardless of how many workspaces are tracked,
        // rather than growing linearly with a serial loop.
        CompletableFuture<?>[] futures = webhooks.stream()
                .map(webhook -> CompletableFuture.runAsync(() -> {
                    try {
                        pollWebhook(webhook);
                    } catch (Exception e) {
                        log.error("Azure DevOps polling error for webhook {}", webhook.getId(), e);
                    }
                }, pollExecutor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
    }

    private void pollWebhook(Webhook webhook) {
        Workspace workspace = webhook.getWorkspace();
        if (workspace == null || workspace.getVcs() == null) {
            return;
        }
        String branch = workspace.getBranch();
        if (branch == null || branch.isEmpty()) {
            log.debug("Skipping Azure DevOps polling for workspace {}: no branch configured", workspace.getId());
            return;
        }

        String latestCommit = azDevOpsWebhookService.getLatestCommit(workspace, branch);
        if (latestCommit == null || latestCommit.isEmpty()) {
            return;
        }

        String key = KEY_PREFIX + workspace.getId() + ":" + branch;
        Object previous = redisTemplate.opsForValue().get(key);
        String previousCommit = previous != null ? previous.toString() : null;

        if (previousCommit == null) {
            // First observation: remember the current tip without triggering a run.
            redisTemplate.opsForValue().set(key, latestCommit);
            log.info("Azure DevOps polling baseline for workspace {} branch {} set to {}",
                    workspace.getName(), branch, latestCommit);
            return;
        }

        if (latestCommit.equals(previousCommit)) {
            return;
        }

        log.info("Azure DevOps polling detected new commit on {}/{}: {} -> {}",
                workspace.getName(), branch, previousCommit, latestCommit);
        WebhookResult result = azDevOpsWebhookService.buildPushResult(workspace, branch, previousCommit, latestCommit);
        boolean triggered = webhookService.triggerPolledPush(webhook.getId(), result);

        // Advance the baseline even if no event matched, so the same commit is not evaluated again.
        redisTemplate.opsForValue().set(key, latestCommit);

        if (triggered) {
            log.info("Azure DevOps polling triggered a plan for workspace {} on commit {}",
                    workspace.getName(), latestCommit);
        }
    }
}
