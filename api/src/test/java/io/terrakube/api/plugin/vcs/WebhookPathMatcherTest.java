package io.terrakube.api.plugin.vcs;

import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebhookPathMatcherTest {

    private final WebhookPathMatcher matcher = new WebhookPathMatcher();

    @Test
    public void matchPatternPathWithWildcard() {
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setPath("terraform/*");
        webhookEvent.setPathType(WebhookEventPathType.PATTERN);

        assertTrue(matcher.matchesAny(List.of("terraform/main.tf"), webhookEvent));
        assertFalse(matcher.matchesAny(List.of("terraform/modules/vpc/main.tf"), webhookEvent));
    }

    @Test
    public void matchPatternPathList() {
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setPath("terraform/*,modules/**");
        webhookEvent.setPathType(WebhookEventPathType.PATTERN);

        assertTrue(matcher.matchesAny(List.of("modules/network/main.tf"), webhookEvent));
    }

    @Test
    public void matchRegexPath() {
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setPath("^bang/.+");
        webhookEvent.setPathType(WebhookEventPathType.REGEX);

        assertTrue(matcher.matchesAny(List.of("bang/main.tf"), webhookEvent));
        assertFalse(matcher.matchesAny(List.of("terraform/main.tf"), webhookEvent));
    }

    @Test
    public void defaultMissingPathTypeToRegexForBackwardsCompatibility() {
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setPath("^terraform/.+");
        webhookEvent.setPathType(null);

        assertTrue(matcher.matchesAny(List.of("terraform/main.tf"), webhookEvent));
        assertFalse(matcher.matchesAny(List.of("modules/main.tf"), webhookEvent));
    }
}
