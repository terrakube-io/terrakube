package io.terrakube.api.plugin.notification.payload;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.terrakube.api.plugin.cost.InfracostSummary;
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
            body.put("costEstimation", buildCostEstimationMap(context.costSummary()));
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render webhook payload", e);
        }
    }

    @Override
    public String enrichCost(String payload, InfracostSummary costSummary) {
        if (costSummary == null || payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            JsonNode tree = objectMapper.readTree(payload);
            if (!(tree instanceof ObjectNode root) || root.has("costEstimation")) {
                return payload;
            }
            root.set("costEstimation", objectMapper.valueToTree(buildCostEstimationMap(costSummary)));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enrich webhook payload with cost estimation", e);
        }
    }

    private static Map<String, Object> buildCostEstimationMap(InfracostSummary cost) {
        Map<String, Object> costNode = new java.util.LinkedHashMap<>();
        costNode.put("status", cost.status());
        costNode.put("currency", cost.currencyOrDefault());
        costNode.put("estimated", true);
        if (cost.totalMonthlyCost() != null) {
            costNode.put("totalMonthlyCost", cost.totalMonthlyCost().toPlainString());
        }
        if (cost.pastTotalMonthlyCost() != null) {
            costNode.put("pastMonthlyCost", cost.pastTotalMonthlyCost().toPlainString());
        }
        if (cost.diffTotalMonthlyCost() != null) {
            costNode.put("diffMonthlyCost", cost.diffTotalMonthlyCost().toPlainString());
        }
        costNode.put("detectedResources", cost.totalDetectedResources());
        costNode.put("supportedResources", cost.totalSupportedResources());
        costNode.put("unsupportedResources", cost.totalUnsupportedResources());
        costNode.put("noPriceResources", cost.totalNoPriceResources());
        if (cost.shareUrl() != null) {
            costNode.put("shareUrl", cost.shareUrl());
        }
        if (cost.errorMessage() != null) {
            costNode.put("errorMessage", cost.errorMessage());
        }
        return costNode;
    }
}
