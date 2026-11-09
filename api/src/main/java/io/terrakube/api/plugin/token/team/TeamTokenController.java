package io.terrakube.api.plugin.token.team;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.token.TokenIssuancePolicy;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.ProjectRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.token.group.Group;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/access-token/v1/teams")
public class TeamTokenController {

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final RbacService rbacService;
    private final TeamTokenService teamTokenService;
    private final TeamRepository teamRepository;
    private final String instanceOwner;
    private final TokenIssuancePolicy tokenIssuancePolicy;

    public TeamTokenController(WorkspaceRepository workspaceRepository, ProjectRepository projectRepository, RbacService rbacService, TeamTokenService teamTokenService, TeamRepository teamRepository, @Value("${io.terrakube.owner}") String instanceOwner, TokenIssuancePolicy tokenIssuancePolicy) {
        this.workspaceRepository = workspaceRepository;
        this.projectRepository = projectRepository;
        this.rbacService = rbacService;
        this.teamTokenService = teamTokenService;
        this.teamRepository = teamRepository;
        this.instanceOwner = instanceOwner;
        this.tokenIssuancePolicy = tokenIssuancePolicy;
    }

    @PostMapping
    public ResponseEntity<TeamToken> createToken(@RequestBody GroupTokenRequest groupTokenRequest,
            Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken principalJwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        tokenIssuancePolicy.requireAllowedIssuer(principalJwt, groupTokenRequest.getDays(), groupTokenRequest.getHours(), groupTokenRequest.getMinutes());
        tokenIssuancePolicy.requireLifetimeWithinLimit(groupTokenRequest.getDays(), groupTokenRequest.getHours(), groupTokenRequest.getMinutes());
        String token = teamTokenService.createTeamToken(
                groupTokenRequest.getGroup(),
                groupTokenRequest.getDays(),
                groupTokenRequest.getHours(),
                groupTokenRequest.getMinutes(),
                groupTokenRequest.getDescription(), principalJwt);
        if (token == null || token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to create a token for group " + groupTokenRequest.getGroup());
        }
        TeamToken teamToken = new TeamToken();
        teamToken.setToken(token);
        return new ResponseEntity<>(teamToken, HttpStatus.CREATED);
    }

    @GetMapping("/current-teams")
    public ResponseEntity<CurrentGroupsResponse> SearchTeams(Principal principal) {
        JwtAuthenticationToken principalJwt = ((JwtAuthenticationToken) principal);
        CurrentGroupsResponse groupList = new CurrentGroupsResponse();
        groupList.setGroups(new ArrayList<String>());
        teamTokenService.getCurrentGroups(principalJwt).forEach(group -> {
            groupList.getGroups().add(group);
        });
        return new ResponseEntity<>(groupList, HttpStatus.ACCEPTED);
    }

    @GetMapping(path = "/permissions/organization/{organizationId}")
    public ResponseEntity<PermissionSet> getPermissions(Principal principal,
            @PathVariable("organizationId") String organizationId) {
        JwtAuthenticationToken principalJwt = ((JwtAuthenticationToken) principal);
        PermissionSet permissions = new PermissionSet();
        List<String> groups = teamTokenService.getCurrentGroups(principalJwt);
        teamRepository.findAllByOrganizationIdAndNameIn(UUID.fromString(organizationId), groups).forEach(group -> {
            permissions.setManageState(permissions.manageState || rbacService.canManageState(group));
            permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(group));
            permissions.setManageModule(permissions.manageModule || rbacService.canManageModule(group));
            permissions.setManageProvider(permissions.manageProvider || rbacService.canManageProvider(group));
            permissions.setManageTemplate(permissions.manageTemplate || rbacService.canManageTemplate(group));
            permissions.setManageVcs(permissions.manageVcs || rbacService.canManageVcs(group));
            permissions.setManageCollection(permissions.manageCollection || rbacService.canManageCollection(group));
            permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(group));
            permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(group));
            permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(group));
            permissions.setManagePermission(permissions.managePermission || rbacService.canManageWorkspace(group));
            permissions.setManagePolicies(permissions.managePolicies || rbacService.canManagePolicies(group));
        });

        if (groups.contains(instanceOwner)) {
            permissions.setManagePermission(true);
            permissions.setManagePolicies(true);
        }

        log.debug("Permissions: {}", permissions);
        return new ResponseEntity<>(permissions, HttpStatus.ACCEPTED);
    }

    @Transactional
    @GetMapping(path = "/permissions/organization/{organizationId}/workspace/{workspaceId}")
    public ResponseEntity<PermissionSet> getPermissionsWorkspace(Principal principal,
                                                        @PathVariable("organizationId") String organizationId, @PathVariable("workspaceId") String workspaceId) {
        JwtAuthenticationToken principalJwt = ((JwtAuthenticationToken) principal);
        PermissionSet permissions = new PermissionSet();
        List<String> groups = teamTokenService.getCurrentGroups(principalJwt);
        teamRepository.findAllByOrganizationIdAndNameIn(UUID.fromString(organizationId), groups).forEach(group -> {
            permissions.setManageState(permissions.manageState || rbacService.canManageState(group));
            permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(group));
            permissions.setManageModule(permissions.manageModule || rbacService.canManageModule(group));
            permissions.setManageProvider(permissions.manageProvider || rbacService.canManageProvider(group));
            permissions.setManageTemplate(permissions.manageTemplate || rbacService.canManageTemplate(group));
            permissions.setManageVcs(permissions.manageVcs || rbacService.canManageVcs(group));
            permissions.setManageCollection(permissions.manageCollection || rbacService.canManageCollection(group));
            permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(group));
            permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(group));
            permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(group));
            permissions.setManagePermission(permissions.managePermission || rbacService.canManageWorkspace(group));
            permissions.setManagePolicies(permissions.managePolicies || rbacService.canManagePolicies(group));
        });

        if (groups.contains(instanceOwner)) {
            permissions.setManagePermission(true);
            permissions.setManagePolicies(true);
        }

        workspaceRepository.findById(UUID.fromString(workspaceId)).ifPresent(workspace -> {
            workspace.getAccess().forEach(access -> {
               permissions.setManageState(permissions.manageState || rbacService.canManageState(access));
               permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(access));
               permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(access));
               permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(access));
               permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(access));
            });
            if (workspace.getProject() != null) {
                workspace.getProject().getProjectAccess().stream()
                        .filter(pa -> groups.contains(pa.getName()))
                        .forEach(pa -> {
                            permissions.setManageState(permissions.manageState || rbacService.canManageState(pa));
                            permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(pa));
                            permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(pa));
                            permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(pa));
                            permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(pa));
                        });
            }
        });

        log.debug("Permissions with Workspace: {}", permissions);
        return new ResponseEntity<>(permissions, HttpStatus.ACCEPTED);
    }

    /**
     * The caller's own effective permissions on a project: their organization team permissions combined with the
     * project access entries of their groups. Workspace-level overrides are not included. Answers 404 when the
     * project is not in the organization or the caller could not see it, so the ids cannot be used to probe.
     */
    @Transactional
    @GetMapping(path = "/permissions/organization/{organizationId}/project/{projectId}")
    public ResponseEntity<PermissionSet> getPermissionsProject(Principal principal,
            @PathVariable("organizationId") String organizationId, @PathVariable("projectId") String projectId) {
        if (!(principal instanceof JwtAuthenticationToken principalJwt)) {
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
        var project = projectRepository.findById(projectUuid)
                .filter(found -> found.getOrganization() != null && organizationUuid.equals(found.getOrganization().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        PermissionSet permissions = new PermissionSet();
        List<String> groups = teamTokenService.getCurrentGroups(principalJwt);
        var organizationTeams = teamRepository.findAllByOrganizationIdAndNameIn(organizationUuid, groups);
        // The same rule as the project read filter: a caller who could not list the project gets the answer for a
        // project that does not exist, so this endpoint cannot be used to find out which projects exist.
        boolean canSeeProject = groups.contains(instanceOwner)
                || organizationTeams.stream().anyMatch(team -> rbacService.canManageWorkspace(team))
                || project.getProjectAccess().stream().anyMatch(access -> groups.contains(access.getName()));
        if (!canSeeProject) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        organizationTeams.forEach(team -> {
            // Only a team that can manage workspaces gives access to a project; a read or plan team does not.
            if (rbacService.canManageWorkspace(team)) {
                permissions.addGrant(team.getName(), team.getRole());
            }
            permissions.setManageState(permissions.manageState || rbacService.canManageState(team));
            permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(team));
            permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(team));
            permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(team));
            permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(team));
            permissions.setManagePolicies(permissions.managePolicies || rbacService.canManagePolicies(team));
            permissions.setManagePermission(permissions.managePermission || rbacService.canManageWorkspace(team));
            permissions.setManageProject(permissions.manageProject || rbacService.canManageWorkspace(team));
        });
        project.getProjectAccess().stream()
                .filter(access -> groups.contains(access.getName()))
                .forEach(access -> {
                    permissions.addGrant(access.getName(), access.getRole());
                    permissions.setManageState(permissions.manageState || rbacService.canManageState(access));
                    permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(access));
                    permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(access));
                    permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(access));
                    permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(access));
                    permissions.setManageProject(permissions.manageProject || rbacService.canManageProject(access));
                });
        if (groups.contains(instanceOwner)) {
            permissions.setManagePermission(true);
            permissions.setManagePolicies(true);
            permissions.setManageProject(true);
        }
        return new ResponseEntity<>(permissions, HttpStatus.ACCEPTED);
    }

    @Transactional
    @DeleteMapping(path = "/{groupTokenId}")
    public ResponseEntity<String> deleteToken(@PathVariable("groupTokenId") String groupTokenId, Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken principalJwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (teamTokenService.deleteToken(groupTokenId, principalJwt))
            return ResponseEntity.accepted().build();
        else
            return ResponseEntity.badRequest().build();
    }

    @GetMapping
    public ResponseEntity<List<Group>> searchToken(Principal principal) {
        return new ResponseEntity<>(teamTokenService.searchToken(((JwtAuthenticationToken) principal)),
                HttpStatus.ACCEPTED);
    }

    @Getter
    @Setter
    private class CurrentGroupsResponse {
        private List<String> groups;
    }

    @Getter
    @Setter
    public static class TeamToken {
        private String token;
    }

    @Getter
    @Setter
    public static class GroupTokenRequest {
        private String group;
        private String description;
        private int days = 0;
        private int minutes = 0;
        private int hours = 0;
    }

    @ToString
    @Getter
    @Setter
    private class PermissionSet {
        private boolean manageState;
        private boolean manageWorkspace;
        private boolean manageModule;
        private boolean manageProvider;
        private boolean manageVcs;
        private boolean manageTemplate;
        private boolean manageCollection;
        private boolean manageJob;
        private boolean planJob;
        private boolean approveJob;
        private boolean managePermission;
        private boolean managePolicies;
        private boolean manageProject;

        // Which teams grant the access and the highest role among them. Only filled in by the project endpoint, and
        // left out of the JSON elsewhere so the other permission responses keep their shape.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<String> grantedThrough = new ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String accessLevel;

        private static final List<String> ROLE_ORDER = List.of("read", "plan", "write", "admin");

        void addGrant(String team, String role) {
            if (!grantedThrough.contains(team)) {
                grantedThrough.add(team);
            }
            String normalized = role == null ? "custom" : role.toLowerCase(java.util.Locale.ROOT);
            int candidate = ROLE_ORDER.indexOf(normalized);
            int current = accessLevel == null ? -2 : ROLE_ORDER.indexOf(accessLevel);
            if (accessLevel == null || candidate > current) {
                accessLevel = normalized;
            }
        }
    }
}
