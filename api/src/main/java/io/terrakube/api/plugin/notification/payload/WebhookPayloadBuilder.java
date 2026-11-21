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
        body.put("workspaceUrl", context.workspaceUrl());
        body.put("commitId", context.commitId());
        body.put("failureReason", context.failureReason());
        body.put("configurationName", context.configurationName());
        if (context.costSummary() != null) {
            var cost = context.costSummary();
            Map<String, Object> costNode = new java.util.LinkedHashMap<>();
            costNode.put("status", cost.status());
            costNode.put("currency", cost.currencyOrDefault());
            costNode.put("estimated", true);
            if (cost.totalMonthlyCost() != null) {
                costNode.put("total_monthly_cost", cost.totalMonthlyCost().toPlainString());
            }
            if (cost.pastTotalMonthlyCost() != null) {
                costNode.put("past_monthly_cost", cost.pastTotalMonthlyCost().toPlainString());
            }
            if (cost.diffTotalMonthlyCost() != null) {
                costNode.put("diff_monthly_cost", cost.diffTotalMonthlyCost().toPlainString());
            }
            costNode.put("detected_resources", cost.totalDetectedResources());
            costNode.put("supported_resources", cost.totalSupportedResources());
            costNode.put("unsupported_resources", cost.totalUnsupportedResources());
            costNode.put("no_price_resources", cost.totalNoPriceResources());
            if (cost.shareUrl() != null) {
                costNode.put("share_url", cost.shareUrl());
            }
            if (cost.errorMessage() != null) {
                costNode.put("error_message", cost.errorMessage());
            }
            body.put("cost_estimation", costNode);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render webhook payload", e);
        }
    }
}
