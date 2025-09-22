package io.terrakube.api.plugin.token.team;

import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.token.group.Group;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
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
@AllArgsConstructor
@RequestMapping("/access-token/v1/teams")
public class TeamTokenController {

    private final WorkspaceRepository workspaceRepository;
    TeamTokenService teamTokenService;
    TeamRepository teamRepository;
    AccessRepository accessRepository;

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
            permissions.setManageState(permissions.manageState || group.isManageState());
            permissions.setManageWorkspace(permissions.manageWorkspace || group.isManageWorkspace());
            permissions.setManageModule(permissions.manageModule || group.isManageModule());
            permissions.setManageProvider(permissions.manageProvider || group.isManageProvider());
            permissions.setManageTemplate(permissions.manageTemplate || group.isManageTemplate());
            permissions.setManageVcs(permissions.manageVcs || group.isManageVcs());
            permissions.setManageCollection(permissions.manageCollection || group.isManageCollection());
            permissions.setManageJob(permissions.manageJob || group.isManageJob());
        });

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
            permissions.setManageState(permissions.manageState || group.isManageState());
            permissions.setManageWorkspace(permissions.manageWorkspace || group.isManageWorkspace());
            permissions.setManageModule(permissions.manageModule || group.isManageModule());
            permissions.setManageProvider(permissions.manageProvider || group.isManageProvider());
            permissions.setManageTemplate(permissions.manageTemplate || group.isManageTemplate());
            permissions.setManageVcs(permissions.manageVcs || group.isManageVcs());
            permissions.setManageCollection(permissions.manageCollection || group.isManageCollection());
            permissions.setManageJob(permissions.manageJob || group.isManageJob());
        });

        workspaceRepository.findById(UUID.fromString(workspaceId)).ifPresent(workspace -> {
            workspace.getAccess().forEach(access -> {
               permissions.setManageState(permissions.manageState || access.isManageState());
               permissions.setManageJob(permissions.manageJob || access.isManageJob());
               permissions.setManageWorkspace(permissions.manageWorkspace || access.isManageWorkspace());
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
    }
}
