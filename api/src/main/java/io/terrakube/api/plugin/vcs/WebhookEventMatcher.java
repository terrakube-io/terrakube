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
            if (globMatch(webhookBranch, branch)) {
                return true;
            }
        }
        return false;
    }

    public static boolean checkFileChanges(List<String> files, WebhookEvent webhookEvent) {
        String[] triggeredPath = webhookEvent.getPath().split(",");
        for (String file : files) {
            for (int i = 0; i < triggeredPath.length; i++) {
                if (globMatch(file, triggeredPath[i].trim())) {
                    log.info("Changed file {} matches set trigger pattern {}", file, triggeredPath[i]);
                    return true;
                }
            }
        }
        log.info("Changed files {} doesn't match any of the trigger path pattern {}", files, triggeredPath);
        return false;
    }

    static boolean globMatch(String input, String globPattern) {
        try {
            String regex = globToSafeRegex(globPattern);
            return input.matches(regex);
        } catch (Exception e) {
            log.warn("Invalid glob pattern '{}': {}", globPattern, e.getMessage());
            return false;
        }
    }

    static String globToSafeRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append(".");
                    break;
                default:
                    if ("()[]{}|+^$\\.".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
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
