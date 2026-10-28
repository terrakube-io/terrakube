package io.terrakube.api.rs.checks.webhook;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
import io.terrakube.api.rs.webhook.Webhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewWebhook.RULE)
public class TeamViewWebhook extends OperationCheck<Webhook> {

    public static final String RULE = "team view webhook";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(Webhook webhook, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (webhook == null) {
            return false;
        }
        log.debug("team view webhook {}", webhook.getId());
        return workspaceCheckSupport.canViewWorkspace(webhook.getWorkspace(), requestScope);
    }
}

