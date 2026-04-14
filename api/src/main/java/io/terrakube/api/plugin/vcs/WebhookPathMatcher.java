package io.terrakube.api.plugin.vcs;

import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import org.springframework.util.AntPathMatcher;

import java.util.List;

public class WebhookPathMatcher {

    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public boolean matchesAny(List<String> files, WebhookEvent webhookEvent) {
        WebhookEventPathType pathType = resolvePathType(webhookEvent);
        String[] configuredPaths = webhookEvent.getPath().split(",");

        for (String file : files) {
            for (String configuredPath : configuredPaths) {
                String candidate = configuredPath.trim();
                if (candidate.isEmpty()) {
                    continue;
                }

                if (matches(file, candidate, pathType)) {
                    return true;
                }
            }
        }

        return false;
    }

    public WebhookEventPathType resolvePathType(WebhookEvent webhookEvent) {
        if (webhookEvent.getPathType() == null) {
            return WebhookEventPathType.REGEX;
        }

        return webhookEvent.getPathType();
    }

    private boolean matches(String file, String candidate, WebhookEventPathType pathType) {
        if (pathType == WebhookEventPathType.REGEX) {
            return file.matches(candidate);
        }

        return antPathMatcher.match(candidate, file);
    }
}
