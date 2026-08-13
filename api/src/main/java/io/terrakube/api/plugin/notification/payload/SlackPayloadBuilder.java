package io.terrakube.api.plugin.notification.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;

@Component
public class SlackPayloadBuilder implements NotificationPayloadBuilder {

    private final ObjectMapper objectMapper;

    public SlackPayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.SLACK;
    }

    @Override
    public String build(NotificationContext context) {
        String statusLabel = statusLabel(context.jobStatus());

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of(
                "type", "header",
                "text", Map.of("type", "plain_text",
                        "text", context.workspaceName() + " - " + statusLabel)));

        StringBuilder contextText = new StringBuilder()
                .append("Organization: ").append(context.organizationName())
                .append(" | Job: #").append(context.jobId());
        if (context.commitId() != null && !context.commitId().isBlank()) {
            contextText.append(" | Commit: ").append(context.commitId());
        }
        blocks.add(Map.of(
                "type", "context",
                "elements", List.of(Map.of("type", "mrkdwn", "text", contextText.toString()))));

        if (context.jobStatus() == JobStatus.failed && context.failureReason() != null
                && !context.failureReason().isBlank()) {
            blocks.add(Map.of(
                    "type", "section",
                    "text", Map.of("type", "mrkdwn", "text", "*Failure reason:* " + context.failureReason())));
        }

        blocks.add(Map.of(
                "type", "actions",
                "elements", List.of(Map.of(
                        "type", "button",
                        "text", Map.of("type", "plain_text", "text", "View Run"),
                        "url", context.runUrl()))));

        // Slack only colors an attachment's left border when blocks are nested inside the
        // legacy "attachments" wrapper - a bare top-level "blocks" array (what chat.postMessage
        // renders edge-to-edge) has no color affordance at all.
        Map<String, Object> attachment = new java.util.LinkedHashMap<>();
        attachment.put("color", statusColor(context.jobStatus()));
        attachment.put("blocks", blocks);

        try {
            return objectMapper.writeValueAsString(Map.of("attachments", List.of(attachment)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Slack payload", e);
        }
    }

    private String statusLabel(JobStatus status) {
        return switch (status) {
            case completed, noChanges -> "✅ Completed";
            case waitingApproval -> "⚠️ Needs Approval";
            case failed, rejected, cancelled -> "❌ " + capitalize(status.name());
            default -> capitalize(status.name());
        };
    }

    private String statusColor(JobStatus status) {
        return switch (status) {
            case completed, noChanges -> "#2eb67d";
            case waitingApproval -> "#ecb22e";
            case failed, rejected, cancelled -> "#e01e5a";
            default -> "#868686";
        };
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
