package io.terrakube.api.rs.hooks.webhook;

import java.util.Optional;

import org.apache.hc.core5.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.rs.webhook.Webhook;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebhookManageHook implements LifeCycleHook<Webhook> {
    @Autowired
    WebhookService webhookService;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Webhook elideEntity, RequestScope requestScope,
                        Optional<ChangeSpec> changes) {
        switch (operation) {
            case CREATE:
            case UPDATE:
                switch (phase) {
                    case PRECOMMIT:
                        if (elideEntity.isMigratedV2()) {
                            webhookService.deleteWorkspaceWebhook(elideEntity);
                            webhookService.createRepoWebhook(elideEntity);
                        } else {
                            try {
                                webhookService.createOrUpdateWorkspaceWebhook(elideEntity);
                            } catch (Exception e) {
                                throw new WebhookManagementException(HttpStatus.SC_FAILED_DEPENDENCY,
                                        "Failed to create/update webhook: " + e.getMessage());
                            }
                        }
                        break;

                    default:
                        break;
                }
                break;
            case DELETE:
                switch (phase) {
                    case POSTCOMMIT:
                        try {
                            webhookService.deleteWorkspaceWebhook(elideEntity);
                        } catch (Exception e) {
                            throw new WebhookManagementException(HttpStatus.SC_FAILED_DEPENDENCY,
                                    "Failed to delete webhook: " + e.getMessage());
                        }
                        break;

                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }

}
