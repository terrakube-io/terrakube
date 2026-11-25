package io.terrakube.api.plugin.project;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.rs.project.Project;
import lombok.AllArgsConstructor;

/**
 * Applies a project's default tags to the workspaces already in the project. Not an entity operation, so it lives
 * next to the permissions endpoint rather than in Elide. The caller must be able to manage the project: an
 * organization team that can manage workspaces, or a team with the admin role on the project. A dry run reports the
 * counts without changing anything, so the UI can show the impact first.
 */
@RestController
@RequestMapping("/access-token/v1/projects")
@AllArgsConstructor
public class ProjectDefaultTagController {

    private final ProjectAccessResolver projectAccess;
    private final TeamTokenService teamTokenService;
    private final ProjectDefaultTagService service;

    @Transactional
    @PostMapping("/organization/{organizationId}/project/{projectId}/default-tags/apply")
    public ProjectDefaultTagService.Result apply(Principal principal, @PathVariable("organizationId") String organizationId,
            @PathVariable("projectId") String projectId, @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        if (!(principal instanceof JwtAuthenticationToken jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Project project = projectAccess.find(organizationId, projectId);
        if (!projectAccess.canManage(project, teamTokenService.getCurrentGroups(jwt))) {
            // Same answer as a missing project, so the endpoint does not reveal which projects exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return service.apply(project, dryRun);
    }
}
