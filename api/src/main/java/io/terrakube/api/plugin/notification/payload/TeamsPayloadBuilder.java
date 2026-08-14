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
public class TeamsPayloadBuilder implements NotificationPayloadBuilder {

    private final ObjectMapper objectMapper;

    public TeamsPayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.TEAMS;
    }

    @Override
    public String build(NotificationContext context) {
        if (context.messageStyle() == NotificationMessageStyle.SIMPLE) {
            return buildCompactPayload(context);
        }

        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(Map.of("title", "Organization", "value", context.organizationName()));
        facts.add(Map.of("title", "Job", "value", "#" + context.jobId()));
        if (context.commitId() != null && !context.commitId().isBlank()) {
            facts.add(Map.of("title", "Commit", "value", context.commitId()));
        }
        if (context.configurationName() != null && !context.configurationName().isBlank()) {
            facts.add(Map.of("title", "Notification", "value", context.configurationName()));
        }

        List<Map<String, Object>> body = new ArrayList<>();
        if (context.workspaceUrl() != null && !context.workspaceUrl().isBlank()) {
            body.add(Map.of("type", "TextBlock", "isSubtle", true, "text", "Run notification for ["
                    + context.organizationName() + "/" + context.workspaceName() + "](" + context.workspaceUrl() + ")"));
        }
        body.add(Map.of("type", "TextBlock", "size", "Large", "weight", "Bolder",
                "text", context.workspaceName() + " - " + context.jobStatus().name()));
        body.add(Map.of("type", "FactSet", "facts", facts));
        if (context.jobStatus() == JobStatus.failed && context.failureReason() != null
                && !context.failureReason().isBlank()) {
            body.add(Map.of("type", "TextBlock", "wrap", true,
                    "text", "Failure reason: " + context.failureReason()));
        }

        Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", body);
        if (context.runUrl() != null && !context.runUrl().isBlank()) {
            card.put("actions", List.of(Map.of(
                    "type", "Action.OpenUrl",
                    "title", "View Run",
                    "url", context.runUrl())));
        }

        Map<String, Object> attachment = Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", card);

        try {
            return objectMapper.writeValueAsString(Map.of("type", "message", "attachments", List.of(attachment)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Teams payload", e);
        }
    }

    // SIMPLE-style configurations get a single-line card instead of the full one - no facts, no
    // failure reason, no View Run button. Unlike Slack, Teams' incoming-webhook envelope has no
    // documented plain-text-only message form, so this still has to be a valid (if minimal)
    // Adaptive Card rather than a bare "text" field.
    private String buildCompactPayload(NotificationContext context) {
        Map<String, Object> body = Map.of("type", "TextBlock", "wrap", true,
                "text", context.workspaceName() + " - " + context.jobStatus().name());

        Map<String, Object> card = Map.of(
                "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                "type", "AdaptiveCard",
                "version", "1.4",
                "body", List.of(body));

        Map<String, Object> attachment = Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", card);

        try {
            return objectMapper.writeValueAsString(Map.of("type", "message", "attachments", List.of(attachment)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Teams payload", e);
        }
    }
}
