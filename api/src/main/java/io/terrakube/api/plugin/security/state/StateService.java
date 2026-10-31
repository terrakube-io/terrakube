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
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.api.plugin.security.authentication.dex.ActiveStepTokenValidator;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.repository.StepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

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

   @Value("${io.terrakube.token.internal:}")
   private String internalJwtSecret;

   @Value("${io.terrakube.token.pat:}")
   private String patJwtSecret;

   @Autowired(required = false)
   private StepRepository stepRepository;

   public enum HttpBackendAccessDecision {
      ALLOWED,
      UNAUTHORIZED,
      FORBIDDEN
   }

   public record HttpBackendAccessResult(
      HttpBackendAccessDecision decision,
      String jobId,
      String stepId
   ) {
      public static HttpBackendAccessResult allowed(String jobId, String stepId) {
         return new HttpBackendAccessResult(HttpBackendAccessDecision.ALLOWED, jobId, stepId);
      }

      public static HttpBackendAccessResult unauthorized() {
         return new HttpBackendAccessResult(HttpBackendAccessDecision.UNAUTHORIZED, null, null);
      }

      public static HttpBackendAccessResult forbidden() {
         return new HttpBackendAccessResult(HttpBackendAccessDecision.FORBIDDEN, null, null);
      }

      public boolean isAllowed() {
         return decision == HttpBackendAccessDecision.ALLOWED;
      }
   }

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

   @Transactional(readOnly = true)
   public HttpBackendAccessResult authorizeHttpBackendAccess(String authorizationHeader, String organizationId, String workspaceId) {
      String rawToken = extractTokenFromAuthorizationHeader(authorizationHeader);
      if (rawToken == null || rawToken.isBlank()) {
         return HttpBackendAccessResult.unauthorized();
      }

      if (internalJwtSecret == null || internalJwtSecret.isBlank()) {
         log.error("Internal JWT secret is not configured; failing closed for HTTP backend");
         return HttpBackendAccessResult.unauthorized();
      }

      try {
         Jwt jwt = buildInternalJwtDecoder().decode(rawToken);
         HttpBackendAccessDecision decision = validateHttpBackendClaims(jwt.getClaims(), organizationId, workspaceId);
         if (decision == HttpBackendAccessDecision.ALLOWED) {
            String jobId = jwt.getClaimAsString("jobId");
            String stepId = jwt.getClaimAsString("stepId");
            return HttpBackendAccessResult.allowed(jobId, stepId);
         }
         return decision == HttpBackendAccessDecision.UNAUTHORIZED
                 ? HttpBackendAccessResult.unauthorized()
                 : HttpBackendAccessResult.forbidden();
      } catch (JwtException ex) {
         if (isValidPatToken(rawToken)) {
            log.warn("User PAT token rejected on executor-only HTTP backend endpoint for org={} workspace={}", organizationId, workspaceId);
            return HttpBackendAccessResult.forbidden();
         }
         log.warn("HTTP backend token authentication failed for org={} workspace={}: {}", organizationId, workspaceId, ex.getMessage());
         return HttpBackendAccessResult.unauthorized();
      } catch (RuntimeException ex) {
         log.warn("Unexpected error validating HTTP backend token: {}", ex.getMessage());
         return HttpBackendAccessResult.unauthorized();
      }
   }

   public HttpBackendAccessDecision validateHttpBackendClaims(Map<String, Object> claims, String organizationId, String workspaceId) {
      if (claims == null || claims.isEmpty()) {
         return HttpBackendAccessDecision.UNAUTHORIZED;
      }

      InternalTokenType tokenType = InternalTokenClassifier.classify(claims);
      if (tokenType != InternalTokenType.WORKSPACE_EXECUTOR) {
         log.warn("Non-workspace-executor token ({}) denied on HTTP backend endpoint for org={} workspace={}",
                 tokenType, organizationId, workspaceId);
         return HttpBackendAccessDecision.FORBIDDEN;
      }

      Object tokenOrgId = claims.get("organizationId");
      Object tokenWsId = claims.get("workspaceId");
      if (organizationId == null || workspaceId == null || tokenOrgId == null || tokenWsId == null) {
         return HttpBackendAccessDecision.FORBIDDEN;
      }

      if (!organizationId.equalsIgnoreCase(tokenOrgId.toString())
              || !workspaceId.equalsIgnoreCase(tokenWsId.toString())) {
         log.warn("HTTP backend scope mismatch: tokenOrg={} tokenWs={} reqOrg={} reqWs={}",
                 tokenOrgId, tokenWsId, organizationId, workspaceId);
         return HttpBackendAccessDecision.FORBIDDEN;
      }

      return HttpBackendAccessDecision.ALLOWED;
   }

   String extractTokenFromAuthorizationHeader(String authorizationHeader) {
      if (authorizationHeader == null || authorizationHeader.isBlank()) {
         return null;
      }
      String trimmed = authorizationHeader.trim();
      if (trimmed.regionMatches(true, 0, "Basic ", 0, 6)) {
         String base64Part = trimmed.substring(6).trim();
         if (base64Part.isEmpty()) {
            return null;
         }
         try {
            String decoded = new String(Base64.getDecoder().decode(base64Part), StandardCharsets.UTF_8);
            int colonIdx = decoded.indexOf(':');
            if (colonIdx <= 0) {
               return null;
            }
            String password = decoded.substring(colonIdx + 1).trim();
            return password.isEmpty() ? null : password;
         } catch (IllegalArgumentException e) {
            return null;
         }
      }
      if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
         String token = trimmed.substring(7).trim();
         return token.isEmpty() ? null : token;
      }
      return null;
   }

   private NimbusJwtDecoder buildInternalJwtDecoder() {
      byte[] secretBytes = Decoders.BASE64URL.decode(internalJwtSecret);
      SecretKey jwtSecretKey = Keys.hmacShaKeyFor(secretBytes);
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
              .macAlgorithm(resolveMacAlgorithm(secretBytes.length))
              .build();
      if (stepRepository != null) {
         decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                 new JwtTimestampValidator(),
                 new ActiveStepTokenValidator(stepRepository)
         ));
      } else {
         decoder.setJwtValidator(new JwtTimestampValidator());
      }
      return decoder;
   }

   private boolean isValidPatToken(String rawToken) {
      if (patJwtSecret == null || patJwtSecret.isBlank()) {
         return false;
      }
      try {
         byte[] secretBytes = Decoders.BASE64URL.decode(patJwtSecret);
         SecretKey jwtSecretKey = Keys.hmacShaKeyFor(secretBytes);
         NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                 .macAlgorithm(resolveMacAlgorithm(secretBytes.length))
                 .build();
         decoder.setJwtValidator(new JwtTimestampValidator());
         Jwt jwt = decoder.decode(rawToken);
         return "Terrakube".equals(jwt.getClaimAsString("iss"));
      } catch (RuntimeException e) {
         return false;
      }
   }

   private static MacAlgorithm resolveMacAlgorithm(int keyLengthBytes) {
      if (keyLengthBytes >= 64) {
         return MacAlgorithm.HS512;
      } else if (keyLengthBytes >= 48) {
         return MacAlgorithm.HS384;
      } else {
         return MacAlgorithm.HS256;
      }
   }
   private boolean noopEnd() {
      return false;
   }
}

