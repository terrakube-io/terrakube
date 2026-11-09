package io.terrakube.api.plugin.project;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.ProjectRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.rs.project.Project;

/**
 * Applies a project's default tags to the workspaces already in the project. Not an entity operation, so it lives
 * next to the permissions endpoint rather than in Elide. The caller must be able to manage the project: an
 * organization team that can manage workspaces, or a team with the admin role on the project. A dry run reports the
 * counts without changing anything, so the UI can show the impact first.
 */
@RestController
@RequestMapping("/access-token/v1/projects")
public class ProjectDefaultTagController {

    private final ProjectRepository projectRepository;
    private final TeamRepository teamRepository;
    private final TeamTokenService teamTokenService;
    private final RbacService rbacService;
    private final ProjectDefaultTagService service;
    private final String instanceOwner;

    public ProjectDefaultTagController(ProjectRepository projectRepository, TeamRepository teamRepository,
            TeamTokenService teamTokenService, RbacService rbacService, ProjectDefaultTagService service,
            @Value("${io.terrakube.owner}") String instanceOwner) {
        this.projectRepository = projectRepository;
        this.teamRepository = teamRepository;
        this.teamTokenService = teamTokenService;
        this.rbacService = rbacService;
        this.service = service;
        this.instanceOwner = instanceOwner;
    }

    @Transactional
    @PostMapping("/organization/{organizationId}/project/{projectId}/default-tags/apply")
    public ProjectDefaultTagService.Result apply(Principal principal, @PathVariable("organizationId") String organizationId,
            @PathVariable("projectId") String projectId, @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        if (!(principal instanceof JwtAuthenticationToken jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        UUID organizationUuid;
        UUID projectUuid;
        try {
            organizationUuid = UUID.fromString(organizationId);
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        Project project = projectRepository.findById(projectUuid)
                .filter(found -> found.getOrganization() != null && organizationUuid.equals(found.getOrganization().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        List<String> groups = teamTokenService.getCurrentGroups(jwt);
        boolean allowed = groups.contains(instanceOwner)
                || teamRepository.findAllByOrganizationIdAndNameIn(organizationUuid, groups).stream()
                        .anyMatch(rbacService::canManageWorkspace)
                || project.getProjectAccess().stream()
                        .anyMatch(access -> groups.contains(access.getName()) && rbacService.canManageProject(access));
        if (!allowed) {
            // Same answer as a missing project, so the endpoint does not reveal which projects exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return service.apply(project, dryRun);
    }
}
