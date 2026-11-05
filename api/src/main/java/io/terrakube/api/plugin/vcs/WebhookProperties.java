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
     * When true, PR triggers evaluate against the target branch and workspace branch matching is enforced.
     * Defaults to false for backward compatibility.
     */
    private boolean prBaseBranchFilter = false;
}
