package io.terrakube.api.plugin.vcs;

import java.util.List;

import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class WebhookEventMatcher {

    private WebhookEventMatcher() {
    }

    public static boolean checkBranch(String webhookBranch, WebhookEvent webhookEvent) {
        String[] branchList = webhookEvent.getBranch().split(",");
        for (String branch : branchList) {
            branch = branch.trim();
            if (webhookBranch.matches(branch)) {
                return true;
            }
        }
        return false;
    }

    public static boolean checkFileChanges(List<String> files, WebhookEvent webhookEvent) {
        String[] triggeredPath = webhookEvent.getPath().split(",");
        for (String file : files) {
            for (int i = 0; i < triggeredPath.length; i++) {
                if (file.matches(triggeredPath[i])) {
                    log.info("Changed file {} matches set trigger pattern {}", file, triggeredPath[i]);
                    return true;
                }
            }
        }
        log.info("Changed files {} doesn't match any of the trigger path pattern {}", files, triggeredPath);
        return false;
    }

    public static String findTemplateId(WebhookResult result, Webhook webhook,
            WebhookEventRepository webhookEventRepository) {
        return webhookEventRepository
                .findByWebhookAndEventOrderByPriorityAsc(webhook,
                        WebhookEventType.valueOf(result.getNormalizedEvent().toUpperCase()))
                .stream()
                .filter(webhookEvent -> checkBranch(result.getBranch(), webhookEvent)
                        && checkFileChanges(result.getFileChanges(), webhookEvent))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No valid template found for the configured webhook event " + result.getEvent()
                                + " normalized " + result.getNormalizedEvent()))
                .getTemplateId();
    }

    public static String findTemplateIdRelease(WebhookResult result, Webhook webhook,
            WebhookEventRepository webhookEventRepository) {
        return webhookEventRepository
                .findByWebhookAndEventOrderByPriorityAsc(webhook,
                        WebhookEventType.valueOf(result.getNormalizedEvent().toUpperCase()))
                .stream()
                .filter(webhookEvent -> checkBranch(result.getBranch(), webhookEvent))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No valid template found for the configured webhook event " + result.getEvent()
                                + " normalized " + result.getNormalizedEvent()))
                .getTemplateId();
    }
}
