package io.terrakube.api.plugin.security.state;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

@Service
public class StateService {
   @Autowired
   private TeamRepository teamRepository;

   @Autowired
   private WorkspaceRepository workspaceRepository;

   @Autowired(required = false)
   private io.terrakube.api.plugin.hardened.RunnerJobGuard runnerJobGuard;

   @Autowired
   private RbacService rbacService;

   @Value("${io.terrakube.owner:}")
   private String instanceOwner;

   public boolean isRunner(Authentication authentication) {
      return authentication instanceof JwtAuthenticationToken jwt && RunnerToken.isRunner(jwt.getTokenAttributes());
   }

   public boolean runnerMayReadPlan(Authentication authentication, String workspaceId, String jobId) {
      if (!isRunner(authentication)) {
         return true;
      }
      java.util.Map<String, Object> claims = ((JwtAuthenticationToken) authentication).getTokenAttributes();
      return workspaceId.equals(RunnerToken.claim(claims, RunnerToken.CLAIM_WORKSPACE_ID))
            && jobId.equals(RunnerToken.claim(claims, RunnerToken.CLAIM_JOB_ID));
   }

   private boolean runnerCanReadState(java.util.Map<String, Object> claims, String organizationId, String workspaceId) {
      String tokenWorkspaceId = RunnerToken.claim(claims, RunnerToken.CLAIM_WORKSPACE_ID);
      if (!organizationId.equals(RunnerToken.claim(claims, RunnerToken.CLAIM_ORGANIZATION_ID)) || tokenWorkspaceId == null) {
         return false;
      }
      Optional<Workspace> workspace;
      try {
         workspace = workspaceRepository.findById(UUID.fromString(workspaceId));
      } catch (IllegalArgumentException e) {
         return false;
      }
      if (workspace.isEmpty() || workspace.get().getOrganization() == null
            || !organizationId.equals(workspace.get().getOrganization().getId().toString())) {
         return false;
      }
      if (runnerJobGuard == null || !runnerJobGuard.jobActive(claims)) {
         return false;
      }
      if (tokenWorkspaceId.equals(workspaceId)) {
         return true;
      }
      String sharedIds = workspace.get().getSharedIds();
      return sharedIds != null && java.util.Arrays.stream(sharedIds.split(","))
            .map(String::trim)
            .anyMatch(tokenWorkspaceId::equals);
   }

   @Transactional(readOnly = true)
   public boolean hasManageStatePermission(Authentication authentication, String organizationId, String workspaceId) {
      if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
         return false;
      }

      if (RunnerToken.isRunner(jwt.getTokenAttributes())) {
         return runnerCanReadState(jwt.getTokenAttributes(), organizationId, workspaceId);
      }

      Object iss = jwt.getTokenAttributes().get("iss");
      if ("TerrakubeInternal".equals(iss)) {
         return true;
      }

      Object email = jwt.getTokenAttributes().get("email");
      if (instanceOwner != null && !instanceOwner.isBlank() && instanceOwner.equals(email)) {
         return true;
      }

      UUID orgUuid;
      UUID wsUuid;
      try {
         orgUuid = UUID.fromString(organizationId);
         wsUuid = UUID.fromString(workspaceId);
      } catch (IllegalArgumentException e) {
         return false;
      }

      Object groupNames = jwt.getTokenAttributes().get("groups");
      if (groupNames == null) {
         return false;
      }
      @SuppressWarnings("unchecked")
      List<String> groups = (List<String>) groupNames;
      List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(orgUuid, groups);
      for (Team team : teams) {
         if (rbacService.canManageState(team)) {
            return true;
         }
      }

      // Validates access at workspace level
      Optional<Workspace> workspaceOptional = workspaceRepository.findById(wsUuid);
      if (workspaceOptional.isPresent()) {
         Workspace ws = workspaceOptional.get();
         List<Access> accessList = ws.getAccess();
         if (accessList != null && !accessList.isEmpty()) {
            for (Access teamAccess : accessList) {
               if (rbacService.canManageState(teamAccess) && groups.contains(teamAccess.getName())) {
                  return true;
               }
            }
         }
         if (ws.getProject() != null && ws.getProject().getProjectAccess() != null) {
            for (ProjectAccess projectAccess : ws.getProject().getProjectAccess()) {
               if (rbacService.canManageState(projectAccess) && groups.contains(projectAccess.getName())) {
                  return true;
               }
            }
         }
      }

      return false;
   }
}
