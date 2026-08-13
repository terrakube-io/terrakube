package io.terrakube.api.plugin.notification.payload;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.rs.notification.NotificationChannelType;

@Component
public class WebhookPayloadBuilder implements NotificationPayloadBuilder {

    private final ObjectMapper objectMapper;

    public WebhookPayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.WEBHOOK;
    }

    @Override
    public String build(NotificationContext context) {
        Map<String, Object> body = new HashMap<>();
        body.put("organization", context.organizationName());
        body.put("workspace", context.workspaceName());
        body.put("jobId", context.jobId());
        body.put("status", context.jobStatus().name());
        body.put("runUrl", context.runUrl());
        body.put("commitId", context.commitId());
        body.put("failureReason", context.failureReason());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render webhook payload", e);
        }
    }
}
