package io.terrakube.api.plugin.token.team;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.terrakube.api.plugin.project.ProjectAccessResolver;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.token.TokenIssuancePolicy;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.Workspace;
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
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/access-token/v1/teams")
public class TeamTokenController {

    private final WorkspaceRepository workspaceRepository;
    private final ProjectAccessResolver projectAccessResolver;
    private final RbacService rbacService;
    private final TeamTokenService teamTokenService;
    private final TeamRepository teamRepository;
    private final String instanceOwner;
    private final TokenIssuancePolicy tokenIssuancePolicy;

    public TeamTokenController(WorkspaceRepository workspaceRepository, ProjectAccessResolver projectAccessResolver, RbacService rbacService, TeamTokenService teamTokenService, TeamRepository teamRepository, @Value("${io.terrakube.owner}") String instanceOwner, TokenIssuancePolicy tokenIssuancePolicy) {
        this.workspaceRepository = workspaceRepository;
        this.projectAccessResolver = projectAccessResolver;
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
        mergeOrganizationGrants(permissions, UUID.fromString(organizationId), groups, false);
        permissions.settleAccessLevel();

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
        UUID organizationUuid = UUID.fromString(organizationId);
        // A workspace of another organization is the same answer as one that does not exist, so a workspace id cannot
        // be used to find out what is configured on it from an organization the caller has nothing to do with.
        Workspace workspace = workspaceRepository.findById(UUID.fromString(workspaceId))
                .filter(found -> found.getOrganization() != null && organizationUuid.equals(found.getOrganization().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        mergeOrganizationGrants(permissions, organizationUuid, groups, false);

        // Only the access rows of the caller's own teams count: the others are other people's access, and counting them
        // would hand their rights, and their names, to anyone who knows the workspace id.
        workspace.getAccess().stream()
                .filter(access -> groups.contains(access.getName()))
                .forEach(access -> {
                    permissions.addGrant(access.getName(), access.getRole());
                    permissions.setManageState(permissions.manageState || rbacService.canManageState(access));
                    permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(access));
                    permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(access));
                    permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(access));
                    permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(access));
                });
        if (workspace.getProject() != null) {
            workspace.getProject().getProjectAccess().stream()
                    .filter(pa -> groups.contains(pa.getName()))
                    .forEach(pa -> mergeProjectAccess(permissions, pa));
        }
        permissions.settleAccessLevel();

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
        var project = projectAccessResolver.find(organizationId, projectId);

        PermissionSet permissions = new PermissionSet();
        List<String> groups = teamTokenService.getCurrentGroups(principalJwt);
        // The same rule as the project read filter: a caller who could not list the project gets the answer for a
        // project that does not exist, so this endpoint cannot be used to find out which projects exist.
        boolean canSeeProject = groups.contains(instanceOwner)
                || teamRepository.findAllByOrganizationIdAndNameIn(project.getOrganization().getId(), groups).stream()
                        .anyMatch(team -> rbacService.canManageWorkspace(team))
                || project.getProjectAccess().stream().anyMatch(access -> groups.contains(access.getName()));
        if (!canSeeProject) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        // Only a team that can manage workspaces gives access to a project; a read or plan team does not.
        mergeOrganizationGrants(permissions, project.getOrganization().getId(), groups, true);
        project.getProjectAccess().stream()
                .filter(access -> groups.contains(access.getName()))
                .forEach(access -> mergeProjectAccess(permissions, access));
        permissions.setManageProject(projectAccessResolver.canManage(project, groups));
        permissions.settleAccessLevel();
        return new ResponseEntity<>(permissions, HttpStatus.ACCEPTED);
    }

    /**
     * Adds what the caller's organization teams allow. {@code onlyManagersGrant} is for the project, where an
     * organization team gives access only when it can manage workspaces; a read or plan team does not, and listing it
     * as a grant would raise the access shown. For the organization and a workspace every team of the caller counts.
     */
    private void mergeOrganizationGrants(PermissionSet permissions, UUID organizationId, List<String> groups,
            boolean onlyManagersGrant) {
        teamRepository.findAllByOrganizationIdAndNameIn(organizationId, groups).forEach(group -> {
            if (!onlyManagersGrant || rbacService.canManageWorkspace(group)) {
                permissions.addGrant(group.getName(), group.getRole());
            }
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
            permissions.addGrant(instanceOwner, "admin");
            permissions.setManagePermission(true);
            permissions.setManagePolicies(true);
        }
    }

    private void mergeProjectAccess(PermissionSet permissions, ProjectAccess access) {
        permissions.addGrant(access.getName(), access.getRole());
        permissions.setManageState(permissions.manageState || rbacService.canManageState(access));
        permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(access));
        permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(access));
        permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(access));
        permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(access));
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

        // Which teams grant the access and the highest role among them. Left out of the JSON when empty, so a response
        // for a caller in no team keeps its old shape.
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> grantedThrough = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String accessLevel;

        private static final List<String> ROLE_ORDER = List.of("read", "plan", "write", "admin");

        // True when one of the grants is a custom role, whose rights are the flags and not a ranked level.
        @com.fasterxml.jackson.annotation.JsonIgnore
        private boolean customGrant;

        void addGrant(String team, String role) {
            if (!grantedThrough.contains(team)) {
                grantedThrough.add(team);
            }
            // The same normalization as RbacV2Service: a blank or unknown role is a custom one.
            String normalized = role == null ? "" : role.trim().toLowerCase(java.util.Locale.ROOT);
            int candidate = ROLE_ORDER.indexOf(normalized);
            if (candidate < 0) {
                customGrant = true;
                if (accessLevel == null) {
                    accessLevel = "custom";
                }
                return;
            }
            int current = accessLevel == null || "custom".equals(accessLevel) ? -1 : ROLE_ORDER.indexOf(accessLevel);
            if (candidate > current) {
                accessLevel = normalized;
            }
        }

        /**
         * Call once every grant is in. A custom role has no rank, so beside a ranked role it must not be outvoted by
         * the lower one: the level is then at least what the flags that came out of all the grants allow.
         */
        void settleAccessLevel() {
            if (!customGrant || accessLevel == null || "custom".equals(accessLevel)) {
                return;
            }
            String fromFlags = manageProject ? "admin" : (manageWorkspace || approveJob) ? "write" : planJob ? "plan" : "read";
            if (ROLE_ORDER.indexOf(fromFlags) > ROLE_ORDER.indexOf(accessLevel)) {
                accessLevel = fromFlags;
            }
        }
    }
}
