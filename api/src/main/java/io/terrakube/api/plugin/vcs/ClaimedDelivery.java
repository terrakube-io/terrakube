package io.terrakube.api.plugin.vcs;

import java.util.Date;

import io.terrakube.api.rs.webhook.RepoWebhook;

record ClaimedDelivery(RepoWebhook repoWebhook, String payload, String headers, int attemptCount, Date lastAttemptAt) {
}
