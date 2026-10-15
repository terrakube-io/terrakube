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
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class StateService {
   @Autowired
   private TeamRepository teamRepository;

   @Autowired
   private WorkspaceRepository workspaceRepository;

   @Autowired
   private RbacService rbacService;

   @Value("${io.terrakube.owner:}")
   private String instanceOwner;

   @Transactional(readOnly = true)
   public boolean hasManageStatePermission(Authentication authentication, String organizationId, String workspaceId) {
      if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
         return false;
      }

      Object iss = jwt.getTokenAttributes().get("iss");
      if ("TerrakubeInternal".equals(iss)) {
         return validateInternalTokenStateAccess(jwt, organizationId, workspaceId, false);
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

   @Transactional(readOnly = true)
   public boolean hasReadStatePermission(Authentication authentication, String organizationId, String workspaceId) {
      if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
         return false;
      }

      Object iss = jwt.getTokenAttributes().get("iss");
      if ("TerrakubeInternal".equals(iss)) {
         return validateInternalTokenStateAccess(jwt, organizationId, workspaceId, true);
      }

      // For standard users / tokens, manage state permission implies read state permission
      return hasManageStatePermission(authentication, organizationId, workspaceId);
   }

   private boolean validateInternalTokenStateAccess(JwtAuthenticationToken jwt, String organizationId, String workspaceId, boolean readOnly) {
      InternalTokenType tokenType = InternalTokenClassifier.classify(jwt.getTokenAttributes());
      if (tokenType == InternalTokenType.EXECUTOR_SERVICE) {
         return true;
      }
      if (tokenType != InternalTokenType.WORKSPACE_EXECUTOR) {
         log.warn("Internal token cannot access state: {}", jwt.getTokenAttributes().get("sub"));
         return false;
      }

      Object tokenWorkspaceId = jwt.getTokenAttributes().get("workspaceId");
      Object tokenOrgId = jwt.getTokenAttributes().get("organizationId");

      if (tokenWorkspaceId == null || tokenOrgId == null || organizationId == null
              || !tokenOrgId.toString().equalsIgnoreCase(organizationId)) {
         log.warn("Workspace executor token organization mismatch or missing claim: tokenWs={} tokenOrg={} reqOrg={}",
                 tokenWorkspaceId, tokenOrgId, organizationId);
         return false;
      }

      // 1. Direct access to own workspace state (manage or read)
      if (tokenWorkspaceId.toString().equalsIgnoreCase(workspaceId)) {
         return true;
      }

      // 2. Cross-workspace operations
      if (!readOnly) {
         log.warn("Cross-workspace state management denied: token workspaceId={} requested workspaceId={}",
                 tokenWorkspaceId, workspaceId);
         return false;
      }

      // 3. Cross-workspace remote state read (terraform_remote_state)
      if (workspaceId != null) {
         UUID wsUuid;
         try {
            wsUuid = UUID.fromString(workspaceId);
         } catch (IllegalArgumentException e) {
            return false;
         }

         Optional<Workspace> workspaceOptional = workspaceRepository.findById(wsUuid);
         if (workspaceOptional.isPresent()) {
            Workspace targetWs = workspaceOptional.get();

            // Verify organization hierarchy if target workspace has org
            if (targetWs.getOrganization() != null && targetWs.getOrganization().getId() != null
                    && !targetWs.getOrganization().getId().toString().equalsIgnoreCase(organizationId)) {
               log.warn("Target workspace organization does not match requested organization");
               return false;
            }

            // Shared globally with all workspaces in the same organization
            if (targetWs.isGlobalRemoteState()) {
               if (targetWs.getOrganization() != null && targetWs.getOrganization().getId() != null
                       && targetWs.getOrganization().getId().toString().equalsIgnoreCase(tokenOrgId.toString())) {
                  log.debug("Remote state read granted via globalRemoteState for workspace {} to consumer {}", workspaceId, tokenWorkspaceId);
                  return true;
               }
            }

            // Explicitly shared with specific workspace IDs
            String sharedIds = targetWs.getSharedIds();
            if (sharedIds != null && !sharedIds.isBlank()) {
               String[] idList = sharedIds.split(",");
               for (String id : idList) {
                  if (id.trim().equalsIgnoreCase(tokenWorkspaceId.toString())) {
                     log.debug("Remote state read granted via sharedIds for workspace {} to consumer {}", workspaceId, tokenWorkspaceId);
                     return true;
                  }
               }
            }
         }
      }

      log.warn("Workspace token {} not authorized to read remote state of workspace {}", tokenWorkspaceId, workspaceId);
      return false;
   }
}

