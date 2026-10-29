package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.repository.TemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class WorkspaceCheckSupport {

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private GroupService groupService;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private TemplateRepository templateRepository;

    public boolean isTemplateInWorkspaceOrganization(Workspace workspace, String templateReference) {
        if (templateReference == null || templateReference.isBlank()) {
            return true;
        }
        if (workspace == null || workspace.getOrganization() == null || workspace.getOrganization().getId() == null) {
            return false;
        }
        try {
            UUID templateUuid = UUID.fromString(templateReference);
            Optional<Template> templateOpt = templateRepository.findById(templateUuid);
            if (templateOpt.isEmpty()) {
                return false;
            }
            Template template = templateOpt.get();
            return template.getOrganization() != null
                    && workspace.getOrganization().getId().equals(template.getOrganization().getId());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid template UUID format: {}", templateReference);
            return false;
        }
    }

    public boolean isTemplateInJobOrganization(Job job) {
        if (job == null || job.getTemplateReference() == null || job.getTemplateReference().isBlank()) {
            return true;
        }
        io.terrakube.api.rs.Organization org = job.getWorkspace() != null && job.getWorkspace().getOrganization() != null
                ? job.getWorkspace().getOrganization()
                : job.getOrganization();
        if (org == null || org.getId() == null) {
            return false;
        }
        try {
            UUID templateUuid = UUID.fromString(job.getTemplateReference());
            Optional<Template> templateOpt = templateRepository.findById(templateUuid);
            if (templateOpt.isEmpty()) {
                return false;
            }
            Template template = templateOpt.get();
            return template.getOrganization() != null
                    && org.getId().equals(template.getOrganization().getId());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid template UUID in job: {}", job.getTemplateReference());
            return false;
        }
    }

    public boolean canViewWorkspace(Workspace workspace, RequestScope requestScope) {
        if (workspace == null || workspace.getOrganization() == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        // 1. Organization tier
        List<Team> teamList = workspace.getOrganization().getTeam();
        if (teamList != null) {
            if (workspace.getProject() == null) {
                if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
                    return true;
                }
            } else {
                if (membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageWorkspace)) {
                    return true;
                }
            }
        }

        // 2. Workspace tier (direct Access bindings)
        List<Access> accessList = workspace.getAccess();
        if (accessList != null && membershipService.checkLimitedMembership(requestScope.getUser(), accessList)) {
            return true;
        }

        // 3. Project tier
        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
            return membershipService.checkProjectMembership(
                    requestScope.getUser(), project.getProjectAccess(), pa -> true);
        }

        return false;
    }

    public boolean canManageWorkspace(Workspace workspace, RequestScope requestScope) {
        if (workspace == null || workspace.getOrganization() == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        // 1. Organization tier
        List<Team> teamList = workspace.getOrganization().getTeam();
        if (teamList != null && membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageWorkspace)) {
            return true;
        }

        // 2. Workspace tier (direct Access bindings)
        List<Access> accessList = workspace.getAccess();
        if (accessList != null && membershipService.checkAccessPermission(requestScope.getUser(), accessList, rbacService::canManageWorkspace)) {
            return true;
        }

        // 3. Project tier
        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
            return membershipService.checkProjectMembership(
                    requestScope.getUser(), project.getProjectAccess(), rbacService::canManageWorkspace);
        }

        return false;
    }
}

