package io.terrakube.api.plugin.project;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.ProjectRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.rs.project.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Finds a project for the token endpoints and says whether a caller's groups may manage it. */
@Component
public class ProjectAccessResolver {

    private final ProjectRepository projectRepository;
    private final TeamRepository teamRepository;
    private final RbacService rbacService;
    private final String instanceOwner;

    public ProjectAccessResolver(ProjectRepository projectRepository, TeamRepository teamRepository,
            RbacService rbacService, @Value("${io.terrakube.owner}") String instanceOwner) {
        this.projectRepository = projectRepository;
        this.teamRepository = teamRepository;
        this.rbacService = rbacService;
        this.instanceOwner = instanceOwner;
    }

    /** The project, or 404 when an id is malformed or the project is not in the organization, so ids cannot probe. */
    public Project find(String organizationId, String projectId) {
        UUID organizationUuid;
        UUID projectUuid;
        try {
            organizationUuid = UUID.fromString(organizationId);
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        return projectRepository.findById(projectUuid)
                .filter(found -> found.getOrganization() != null && organizationUuid.equals(found.getOrganization().getId()))
                .orElseThrow(ProjectAccessResolver::notFound);
    }

    /** An organization team that can manage workspaces, a team with the admin role on the project, or the instance owner. */
    public boolean canManage(Project project, List<String> groups) {
        return groups.contains(instanceOwner)
                || teamRepository.findAllByOrganizationIdAndNameIn(project.getOrganization().getId(), groups).stream()
                        .anyMatch(rbacService::canManageWorkspace)
                || project.getProjectAccess().stream()
                        .anyMatch(access -> groups.contains(access.getName()) && rbacService.canManageProject(access));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }
}
