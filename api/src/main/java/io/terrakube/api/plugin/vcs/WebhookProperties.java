package io.terrakube.api.plugin.vcs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for VCS webhooks.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.webhook")
public class WebhookProperties {

    /**
     * Whether to filter pull request webhooks and PR comments by target (base) branch.
     * When true, workspace qualification verifies that the PR's target (base) branch matches the workspace's configured branch.
     * Trigger branch patterns continue to match against the PR source (head) branch.
     * Defaults to false for backward compatibility.
     */
    private boolean prBaseBranchFilter = false;
}
