package io.terrakube.api.plugin.security.state;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.InternalTokens;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

@Service
public class StateService {
   @Autowired
   private TeamRepository teamRepository;

   @Autowired
   private WorkspaceRepository workspaceRepository;

   @Autowired
   private RbacService rbacService;

   @Transactional(readOnly = true)
   public boolean hasReadStatePermission(Authentication authentication, String organizationId, String workspaceId) {
      String jobWorkspaceId = InternalTokens.jobWorkspaceId(((JwtAuthenticationToken) authentication).getTokenAttributes());
      if (jobWorkspaceId != null) {
         return canJobReadState(jobWorkspaceId, workspaceId)
               && workspaceRepository.findById(UUID.fromString(workspaceId))
                     .map(workspace -> workspace.getOrganization().getId().toString().equals(organizationId))
                     .orElse(false);
      }
      return hasManageStatePermission(authentication, organizationId, workspaceId);
   }

   /**
    * A job may read its own workspace's state, and state of workspaces in its organization that
    * share state globally or with its workspace by id.
    */
   @Transactional(readOnly = true)
   public boolean canJobReadState(String jobWorkspaceId, String workspaceId) {
      if (jobWorkspaceId.equals(workspaceId)) {
         return true;
      }
      Optional<Workspace> target = workspaceRepository.findById(UUID.fromString(workspaceId));
      Optional<Workspace> job = workspaceRepository.findById(UUID.fromString(jobWorkspaceId));
      if (target.isEmpty() || job.isEmpty()
            || !target.get().getOrganization().getId().equals(job.get().getOrganization().getId())) {
         return false;
      }
      if (target.get().isGlobalRemoteState()) {
         return true;
      }
      String sharedIds = target.get().getSharedIds();
      return sharedIds != null && Arrays.stream(sharedIds.split(",")).map(String::trim).anyMatch(jobWorkspaceId::equals);
   }

   @Transactional
   public boolean hasManageStatePermission(Authentication authentication, String organizationId, String workspaceId) {
      if (InternalTokens.isService(authentication)) {
         return true;
      } else {
         Object groupNames = ((JwtAuthenticationToken) authentication).getTokenAttributes().get("groups");
         if (groupNames == null) {
            return false;
         }
         @SuppressWarnings("unchecked")
         List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(UUID.fromString(organizationId), (List<String>) groupNames);
         for (Team team : teams) {
            if (rbacService.canManageState(team)) {
               return true;
            }
         }

         // Validates access at workspace level
          Optional<Workspace> workspaceOptional = workspaceRepository.findById(UUID.fromString(workspaceId));
          if (workspaceOptional.isPresent()) {
              List<Access> accessList = workspaceOptional.get().getAccess();
              if (!accessList.isEmpty())
                  for (Access teamAccess : accessList) {
                      if (rbacService.canManageState(teamAccess) && ((List<String>) groupNames).contains(teamAccess.getName())) {
                          return true;
                      }
                  }
          }

         return false;
      }
   }
}
