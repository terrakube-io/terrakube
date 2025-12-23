package io.terrakube.api.rs.hooks.workspace;

import java.util.Optional;

import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.workspace.Workspace;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class WorkspaceManageHook implements LifeCycleHook<Workspace> {

    SoftDeleteService softDeleteService;
    WebhookService webhookService;
    WebhookRepository webhookRepository;
    GlobalVarRepository globalVarRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Workspace workspace, RequestScope requestScope,
            Optional<ChangeSpec> optional) {
        log.info("Workspace mutation hook for workspace {}/{}", workspace.getOrganization().getName(), workspace.getName());
        switch (operation) {
            case UPDATE:
                if (workspace.isDeleted()) {
                    softDeleteService.disableWorkspaceSchedules(workspace);
                }
                break;
            case CREATE:
                switch (transactionPhase) {
                    case PRECOMMIT:
                        if (workspace.getExecutionMode() == null) {
                            log.debug("setting default execution mode");
                            workspace.setExecutionMode(workspace.getOrganization().getExecutionMode());
                        }
                        break;

                    default:
                        break;
                }
            default:
                log.info("No hook define for this operation");
                break;
        }
    }
}
