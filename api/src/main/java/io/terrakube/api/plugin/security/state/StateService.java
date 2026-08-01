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
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
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
   private JobRepository jobRepository;

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

   /**
    * Step-output endpoints are keyed by org/job/step and carry no workspace in
    * the path. Resolve the job to its workspace, confirm it belongs to the org
    * in the path, then reuse the per-workspace manage-state check. Internal
    * tokens short-circuit to true inside {@link #hasManageStatePermission}.
    */
   @Transactional
   public boolean hasManageStatePermissionByJob(Authentication authentication, String organizationId, String jobId) {
      Job job;
      try {
         job = jobRepository.findById(Integer.valueOf(jobId)).orElse(null);
      } catch (NumberFormatException e) {
         return false;
      }
      if (job == null || job.getWorkspace() == null || job.getWorkspace().getOrganization() == null) {
         return false;
      }
      Workspace workspace = job.getWorkspace();
      // Guard against a mismatched org in the path pointing at a job from another org.
      if (!workspace.getOrganization().getId().toString().equals(organizationId)) {
         return false;
      }
      return hasManageStatePermission(authentication, organizationId, workspace.getId().toString());
   }
}
