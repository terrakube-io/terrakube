package io.terrakube.api.rs.checks.webhook;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
import io.terrakube.api.rs.webhook.WebhookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageWebhookEvent.RULE)
public class TeamManageWebhookEvent extends OperationCheck<WebhookEvent> {

    public static final String RULE = "team manage webhook event";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(WebhookEvent webhookEvent, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (webhookEvent == null || webhookEvent.getWebhook() == null) {
            return false;
        }
        log.debug("team manage webhook event {}", webhookEvent.getId());
        return workspaceCheckSupport.canManageWorkspace(webhookEvent.getWebhook().getWorkspace(), requestScope);
    }
}

