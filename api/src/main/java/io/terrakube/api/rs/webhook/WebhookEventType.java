package io.terrakube.api.rs.webhook;

public enum WebhookEventType {
    PUSH,
    PULL_REQUEST,
    PR_COMMENT,
    PING,
    RELEASE,
    UNKNOWN
}