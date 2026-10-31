package io.terrakube.api.rs.checks.webhook;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamDeleteWebhookEvent.RULE)
public class TeamDeleteWebhookEvent extends OperationCheck<WebhookEvent> {

    public static final String RULE = "team delete webhook event";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(WebhookEvent webhookEvent, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (webhookEvent == null || webhookEvent.getWebhook() == null) {
            return false;
        }
        log.debug("team delete webhook event {}", webhookEvent.getId());
        Workspace workspace = webhookEvent.getWebhook().getWorkspace();
        return workspaceCheckSupport.canManageWorkspace(workspace, requestScope);
    }
}

