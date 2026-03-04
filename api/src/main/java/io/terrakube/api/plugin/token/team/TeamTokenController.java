package io.terrakube.api.plugin.token.team;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.AccessRepository;
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

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/access-token/v1/teams")
public class TeamTokenController {

    private final WorkspaceRepository workspaceRepository;
    private final RbacService rbacService;
    private final TeamTokenService teamTokenService;
    private final TeamRepository teamRepository;
    private final String instanceOwner;

    public TeamTokenController(WorkspaceRepository workspaceRepository, RbacService rbacService, TeamTokenService teamTokenService, TeamRepository teamRepository, @Value("${io.terrakube.owner}") String instanceOwner) {
        this.workspaceRepository = workspaceRepository;
        this.rbacService = rbacService;
        this.teamTokenService = teamTokenService;
        this.teamRepository = teamRepository;
        this.instanceOwner = instanceOwner;
    }

    @PostMapping
    public ResponseEntity<TeamToken> createToken(@RequestBody GroupTokenRequest groupTokenRequest,
            Principal principal) {
        TeamToken teamToken = new TeamToken();
        teamToken.setToken(teamTokenService.createTeamToken(
                groupTokenRequest.getGroup(),
                groupTokenRequest.getDays(),
                groupTokenRequest.getHours(),
                groupTokenRequest.getMinutes(),
                groupTokenRequest.getDescription(), ((JwtAuthenticationToken) principal)));
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
        });

        if (groups.contains(instanceOwner)) {
            permissions.setManagePermission(true);
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
        });

        workspaceRepository.findById(UUID.fromString(workspaceId)).ifPresent(workspace -> {
            workspace.getAccess().forEach(access -> {
               permissions.setManageState(permissions.manageState || rbacService.canManageState(access));
               permissions.setManageJob(permissions.manageJob || rbacService.canManageJob(access));
               permissions.setManageWorkspace(permissions.manageWorkspace || rbacService.canManageWorkspace(access));
               permissions.setPlanJob(permissions.planJob || rbacService.canPlanJob(access));
               permissions.setApproveJob(permissions.approveJob || rbacService.canApproveJob(access));
            });
        });

        log.debug("Permissions with Workspace: {}", permissions);
        return new ResponseEntity<>(permissions, HttpStatus.ACCEPTED);
    }

    @Transactional
    @DeleteMapping(path = "/{groupTokenId}")
    public ResponseEntity<String> deleteToken(@PathVariable("groupTokenId") String groupTokenId) {
        if (teamTokenService.deleteToken(groupTokenId))
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
    private static class GroupTokenRequest {
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
    }
}
