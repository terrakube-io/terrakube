package io.terrakube.api.rs.hooks.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.hc.core5.http.HttpStatus;

import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
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
    WorkspaceRepository workspaceRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Workspace workspace, RequestScope requestScope,
            Optional<ChangeSpec> optional) {
        log.info("Workspace mutation hook for workspace {}/{}", workspace.getOrganization().getName(), workspace.getName());
        switch (operation) {
            case UPDATE:
                switch (transactionPhase) {
                    case PRECOMMIT:
                        if (optional.isPresent()) {
                            String fieldName = optional.get().getFieldName();
                            if ("name".equals(fieldName)) {
                                validateWorkspaceNameConflict(workspace);
                            } else if ("agent".equals(fieldName) || "vcs".equals(fieldName) || "ssh".equals(fieldName) || "project".equals(fieldName)) {
                                validateWorkspaceRelationships(workspace);
                            } else if ("deleted".equals(fieldName)) {
                                if (workspace.isDeleted()) {
                                    softDeleteService.disableWorkspaceSchedules(workspace);
                                }
                            }
                        } else {
                            validateWorkspaceNameConflict(workspace);
                            validateWorkspaceRelationships(workspace);
                            if (workspace.isDeleted()) {
                                softDeleteService.disableWorkspaceSchedules(workspace);
                            }
                        }
                        break;
                    default:
                        break;
                }
                break;
            case CREATE:
                switch (transactionPhase) {
                    case PRECOMMIT:
                        validateWorkspaceNameConflict(workspace);
                        validateWorkspaceRelationships(workspace);

                        if (workspace.getExecutionMode() == null) {
                            log.debug("setting default execution mode");
                            workspace.setExecutionMode(workspace.getOrganization().getExecutionMode());
                        }
                        break;

                    default:
                        break;
                }
                break;
            default:
                log.info("No hook define for this operation");
                break;
        }
    }

    private void validateWorkspaceNameConflict(Workspace workspace) {
        if (workspace.getOrganization() != null && workspace.getOrganization().getName() != null && workspace.getName() != null) {
            List<Workspace> matches = workspaceRepository.findAllByOrganizationNameAndName(
                    workspace.getOrganization().getName(), workspace.getName());
            boolean conflict = matches.stream()
                    .anyMatch(match -> workspace.getId() == null || !match.getId().equals(workspace.getId()));
            if (conflict) {
                throw new WorkspaceManagementException(HttpStatus.SC_CONFLICT,
                        "A workspace named " + workspace.getName() + " already exists in this organization.");
            }
        }
    }

    private void validateWorkspaceRelationships(Workspace workspace) {
        if (workspace.getOrganization() == null || workspace.getOrganization().getId() == null) {
            return;
        }
        UUID orgId = workspace.getOrganization().getId();
        if (workspace.getAgent() != null) {
            UUID agentOrgId = workspace.getAgent().getOrganization() != null
                    ? workspace.getAgent().getOrganization().getId()
                    : null;
            if (agentOrgId == null || !orgId.equals(agentOrgId)) {
                throw new WorkspaceManagementException(HttpStatus.SC_FORBIDDEN,
                        "Agent does not belong to the workspace organization");
            }
        }
        if (workspace.getVcs() != null) {
            UUID vcsOrgId = workspace.getVcs().getOrganization() != null
                    ? workspace.getVcs().getOrganization().getId()
                    : null;
            if (vcsOrgId == null || !orgId.equals(vcsOrgId)) {
                throw new WorkspaceManagementException(HttpStatus.SC_FORBIDDEN,
                        "VCS does not belong to the workspace organization");
            }
        }
        if (workspace.getSsh() != null) {
            UUID sshOrgId = workspace.getSsh().getOrganization() != null
                    ? workspace.getSsh().getOrganization().getId()
                    : null;
            if (sshOrgId == null || !orgId.equals(sshOrgId)) {
                throw new WorkspaceManagementException(HttpStatus.SC_FORBIDDEN,
                        "SSH does not belong to the workspace organization");
            }
        }
        if (workspace.getProject() != null) {
            UUID projectOrgId = workspace.getProject().getOrganization() != null
                    ? workspace.getProject().getOrganization().getId()
                    : null;
            if (projectOrgId == null || !orgId.equals(projectOrgId)) {
                throw new WorkspaceManagementException(HttpStatus.SC_FORBIDDEN,
                        "Project does not belong to the workspace organization");
            }
        }
    }
}
