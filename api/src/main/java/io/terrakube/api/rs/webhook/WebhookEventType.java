package io.terrakube.api.rs.webhook;

public enum WebhookEventType {
    PUSH,
    PULL_REQUEST,
    PING,
    RELEASE,
    UNKNOWN
}