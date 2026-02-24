package io.terrakube.api.plugin.security.state;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.security.rbac.RbacService;
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

   @Transactional
   public boolean hasManageStatePermission(Authentication authentication, String organizationId, String workspaceId) {
      if (((JwtAuthenticationToken) authentication).getTokenAttributes().get("iss").equals("TerrakubeInternal")) {
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
