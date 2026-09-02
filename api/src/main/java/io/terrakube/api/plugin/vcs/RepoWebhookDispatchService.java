package io.terrakube.api.plugin.vcs;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RepoWebhookDispatchService {

    static final int MAX_ATTEMPTS = 3;
    private static final long ONE_MINUTE_MILLIS = 60_000L;
    private static final long FIVE_MINUTES_MILLIS = 5 * ONE_MINUTE_MILLIS;

    private final RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;
    private final RepoWebhookService repoWebhookService;
    private final ObjectMapper objectMapper;

    public RepoWebhookDispatchService(RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions,
            RepoWebhookService repoWebhookService, ObjectMapper objectMapper) {
        this.repoWebhookDeliveryTransactions = repoWebhookDeliveryTransactions;
        this.repoWebhookService = repoWebhookService;
        this.objectMapper = objectMapper;
    }

    @Async("repoWebhookDispatchExecutor")
    public void dispatchAsync(UUID deliveryId) {
        attemptDelivery(deliveryId);
    }

    // claim() and recordResult() each open their own short transaction on a separate bean
    // (RepoWebhookDeliveryTransactions) - the workspace fan-out in between (parsing, matching
    // templates, calling out to GitHub/GitLab/Azure DevOps for PR files and commit statuses,
    // scheduling Quartz job triggers) runs with no transaction held for its duration. This is the
    // fix for the original bug: the old synchronous processV2Webhook held one open transaction
    // (and one pooled DB connection) across the entire loop of up to dozens of workspaces and their
    // external API calls, which is also what made a 30-workspace shared webhook exceed GitHub's
    // 10-second delivery timeout.
    public void attemptDelivery(UUID deliveryId) {
        ClaimedDelivery claimed = repoWebhookDeliveryTransactions.claim(deliveryId);
        if (claimed == null) {
            return;
        }

        RepoWebhookDeliveryStatus newStatus;
        Date nextAttemptAt = null;
        String lastError = null;
        try {
            Map<String, String> headers = deserializeHeaders(claimed.headers());
            repoWebhookService.processClaimedDelivery(claimed.repoWebhook(), claimed.payload(), headers);
            newStatus = RepoWebhookDeliveryStatus.PROCESSED;
        } catch (Exception e) {
            // Anything that reaches here failed BEFORE (or while establishing) the per-workspace
            // fan-out - e.g. an unparseable payload, or a transient DB error listing workspaces.
            // Once the fan-out loop itself starts, RepoWebhookService.processClaimedDelivery catches
            // and logs every per-workspace failure individually and never lets one propagate here,
            // specifically so a retry of the whole delivery can never re-create a Job for a
            // workspace whose job was already created and scheduled on an earlier attempt.
            lastError = e.getMessage();
            if (claimed.attemptCount() >= MAX_ATTEMPTS) {
                newStatus = RepoWebhookDeliveryStatus.FAILED;
                log.warn("Repo webhook delivery {} permanently failed after {} attempts: {}", deliveryId,
                        claimed.attemptCount(), lastError, e);
            } else {
                newStatus = RepoWebhookDeliveryStatus.PENDING;
                nextAttemptAt = new Date(System.currentTimeMillis() + backoffMillis(claimed.attemptCount()));
                log.warn("Repo webhook delivery {} attempt {} failed, will retry: {}", deliveryId,
                        claimed.attemptCount(), lastError, e);
            }
        }

        repoWebhookDeliveryTransactions.recordResult(deliveryId, claimed.lastAttemptAt(), newStatus, lastError,
                nextAttemptAt);
    }

    private Map<String, String> deserializeHeaders(String headersJson) throws Exception {
        // Stored as plain JSON, so it rehydrates into a case-sensitive map - re-wrap it so the
        // provider services' lowercase header lookups keep working (see WebhookHeaders).
        return WebhookHeaders.caseInsensitive(
                objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {
                }));
    }

    private long backoffMillis(int attemptCount) {
        return attemptCount <= 1 ? ONE_MINUTE_MILLIS : FIVE_MINUTES_MILLIS;
    }
}
