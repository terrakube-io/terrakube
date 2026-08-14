package io.terrakube.api.plugin.notification.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationMessageStyle;

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

        if (context.messageStyle() == NotificationMessageStyle.SIMPLE) {
            return buildCompactPayload(context, statusLabel);
        }

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

        if (context.runUrl() != null && !context.runUrl().isBlank()) {
            blocks.add(Map.of(
                    "type", "actions",
                    "elements", List.of(Map.of(
                            "type", "button",
                            "text", Map.of("type", "plain_text", "text", "View Run"),
                            "url", context.runUrl()))));
        }

        // A workspace's org-wide and workspace-scoped configurations both fire independently (by
        // design - see NotificationConfigResolver), so the same job can post more than one Slack
        // message. Naming which configuration sent each one is what makes that additive behavior
        // legible instead of looking like an accidental duplicate.
        if (context.configurationName() != null && !context.configurationName().isBlank()) {
            blocks.add(Map.of(
                    "type", "context",
                    "elements", List.of(Map.of("type", "mrkdwn",
                            "text", "Sent by notification: *" + context.configurationName() + "*"))));
        }

        // Slack only colors an attachment's left border when blocks are nested inside the
        // legacy "attachments" wrapper - a bare top-level "blocks" array (what chat.postMessage
        // renders edge-to-edge) has no color affordance at all.
        Map<String, Object> attachment = new java.util.LinkedHashMap<>();
        attachment.put("color", statusColor(context.jobStatus()));
        attachment.put("blocks", blocks);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        // Incoming Webhooks reject a payload with no top-level "text" (error "no_text"). Once
        // blocks live inside "attachments" (needed for the color bar), Slack always renders this
        // as a separate line above the card rather than hiding it - so instead of repeating the
        // header (a pure duplicate), it carries the "Run notification for org/workspace" link.
        // That also makes it the text shown in OS/push notification previews and whenever link
        // previews are collapsed, so a user can still get to the workspace from just that line.
        payload.put("text", context.workspaceUrl() != null && !context.workspaceUrl().isBlank()
                ? "Run notification for <" + context.workspaceUrl() + "|" + context.organizationName() + "/"
                        + context.workspaceName() + ">"
                : context.workspaceName() + " - " + statusLabel);
        // Overrides the webhook's default bot name/avatar so messages from different workspaces
        // posting into the same channel are visually distinguishable at a glance.
        payload.put("username", context.workspaceName());
        payload.put("attachments", List.of(attachment));

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Slack payload", e);
        }
    }

    // SIMPLE-style configurations get a single-line ping for every status instead of the full
    // card - this is a plain top-level "text" message (no blocks/attachments): Slack only shows
    // the visible-duplicate-line behavior when blocks are present, so the simplest way to avoid
    // it here is to not use blocks at all, since there's nothing more to say than the one line.
    private String buildCompactPayload(NotificationContext context, String statusLabel) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("text", context.workspaceName() + " - " + statusLabel);
        payload.put("username", context.workspaceName());
        try {
            return objectMapper.writeValueAsString(payload);
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
