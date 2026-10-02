package io.terrakube.api.plugin.vcs;

import java.util.Date;

import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.RepoWebhook;

// vcs is non-null only for a GitHub App delivery (see RepoWebhookDelivery.vcs).
record ClaimedDelivery(RepoWebhook repoWebhook, Vcs vcs, String payload, String headers, int attemptCount,
        Date lastAttemptAt) {
}
