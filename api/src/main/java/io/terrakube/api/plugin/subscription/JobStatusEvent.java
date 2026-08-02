package io.terrakube.api.plugin.subscription;

public record JobStatusEvent(int jobId, String workspaceId, String status) {
}
