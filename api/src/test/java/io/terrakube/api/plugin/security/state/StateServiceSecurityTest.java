package io.terrakube.api.plugin.security.state;

import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class StateServiceSecurityTest {

    private StateService stateService;
    private WorkspaceRepository workspaceRepository;
    private io.terrakube.api.repository.TeamRepository teamRepository;
    private io.terrakube.api.plugin.security.rbac.RbacService rbacService;
    private io.terrakube.api.repository.StepRepository stepRepository;
    private UUID orgId;
    private UUID wsId;

    @BeforeEach
    void setUp() {
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        teamRepository = Mockito.mock(io.terrakube.api.repository.TeamRepository.class);
        rbacService = Mockito.mock(io.terrakube.api.plugin.security.rbac.RbacService.class);
        stepRepository = Mockito.mock(io.terrakube.api.repository.StepRepository.class);

        stateService = new StateService(teamRepository, workspaceRepository, rbacService, stepRepository);

        orgId = UUID.randomUUID();
        wsId = UUID.randomUUID();
    }

    @Test
    void testManageStateOwnWorkspaceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateDifferentWorkspaceReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        UUID otherWsId = UUID.randomUUID();
        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), otherWsId.toString()));
    }

    @Test
    void testManageStateDifferentOrganizationReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        UUID otherOrgId = UUID.randomUUID();
        assertFalse(stateService.hasManageStatePermission(jwt, otherOrgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateExecutorServiceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));

        assertTrue(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateUnknownInternalFailsClosed() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateOwnWorkspaceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceGlobalSharingSameOrgReturnsTrue() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization org = new Organization();
        org.setId(orgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(org);
        targetWs.setGlobalRemoteState(true);

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceGlobalSharingDiffOrgReturnsFalse() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();
        UUID otherOrgId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization targetOrg = new Organization();
        targetOrg.setId(otherOrgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(targetOrg);
        targetWs.setGlobalRemoteState(true);

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertFalse(stateService.hasReadStatePermission(jwt, otherOrgId.toString(), targetWsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceExplicitSharedIdsReturnsTrue() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization org = new Organization();
        org.setId(orgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(org);
        targetWs.setGlobalRemoteState(false);
        targetWs.setSharedIds(UUID.randomUUID() + "," + consumerWsId + "," + UUID.randomUUID());

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceNotSharedReturnsFalse() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization org = new Organization();
        org.setId(orgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(org);
        targetWs.setGlobalRemoteState(false);
        targetWs.setSharedIds(UUID.randomUUID().toString());

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertFalse(stateService.hasReadStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testCrossWorkspaceConsumerCannotManageOrRollbackTargetWorkspace() {
        // Even if target workspace shares state globally, consumer token must NEVER have manage/rollback permission
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testManageStateMissingOrgIdReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateMissingOrgIdReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertFalse(stateService.hasReadStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateUserTokenWithManageStatePermissionReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://dex.example.com",
                "groups", java.util.List.of("dev-team")
        ));

        io.terrakube.api.rs.team.Team team = new io.terrakube.api.rs.team.Team();
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, java.util.List.of("dev-team")))
                .thenReturn(java.util.List.of(team));
        when(rbacService.canManageState(team)).thenReturn(true);

        assertTrue(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateUserTokenWithoutManageStatePermissionReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://dex.example.com",
                "groups", java.util.List.of("dev-team")
        ));

        io.terrakube.api.rs.team.Team team = new io.terrakube.api.rs.team.Team();
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, java.util.List.of("dev-team")))
                .thenReturn(java.util.List.of(team));
        when(rbacService.canManageState(team)).thenReturn(false);
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.empty());

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testHttpBackendAccessAllowedWithValidBasicAuthWorkspaceToken() {
        ReflectionTestUtils.setField(stateService, "internalJwtSecret", TEST_INTERNAL_SECRET);
        UUID stepId = UUID.randomUUID();
        io.terrakube.api.rs.job.step.Step activeStep = new io.terrakube.api.rs.job.step.Step();
        activeStep.setId(stepId);
        activeStep.setStatus(io.terrakube.api.rs.job.JobStatus.running);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(activeStep));

        String token = signToken(TEST_INTERNAL_SECRET, "TerrakubeInternal", "TerrakubeInternal (TOKEN)", Map.of(
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString(),
                "jobId", "101",
                "stepId", stepId.toString()
        ), 300);

        StateService.HttpBackendAccessResult basicResult = stateService.authorizeHttpBackendAccess(basicHeader("terraform", token), orgId.toString(), wsId.toString());
        assertEquals(StateService.HttpBackendAccessDecision.ALLOWED, basicResult.decision());
        assertEquals("101", basicResult.jobId());
        assertEquals(stepId.toString(), basicResult.stepId());

        StateService.HttpBackendAccessResult bearerResult = stateService.authorizeHttpBackendAccess("Bearer " + token, orgId.toString(), wsId.toString());
        assertEquals(StateService.HttpBackendAccessDecision.ALLOWED, bearerResult.decision());
        assertEquals("101", bearerResult.jobId());
        assertEquals(stepId.toString(), bearerResult.stepId());
    }

    @Test
    void testHttpBackendAccessUnauthorizedWhenMissingOrMalformedHeaders() {
        ReflectionTestUtils.setField(stateService, "internalJwtSecret", TEST_INTERNAL_SECRET);

        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess(null, orgId.toString(), wsId.toString()).decision());
        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess("", orgId.toString(), wsId.toString()).decision());
        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess("Basic !!!not-base64!!!", orgId.toString(), wsId.toString()).decision());
        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess(basicHeader("", "some-token"), orgId.toString(), wsId.toString()).decision());
        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", ""), orgId.toString(), wsId.toString()).decision());
    }

    @Test
    void testHttpBackendAccessUnauthorizedWhenTokenExpiredOrStepCompleted() {
        ReflectionTestUtils.setField(stateService, "internalJwtSecret", TEST_INTERNAL_SECRET);
        UUID stepId = UUID.randomUUID();
        io.terrakube.api.rs.job.step.Step completedStep = new io.terrakube.api.rs.job.step.Step();
        completedStep.setId(stepId);
        completedStep.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(completedStep));

        String activeTokenWithCompletedStep = signToken(TEST_INTERNAL_SECRET, "TerrakubeInternal", "TerrakubeInternal (TOKEN)", Map.of(
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString(),
                "jobId", "101",
                "stepId", stepId.toString()
        ), 300);

        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", activeTokenWithCompletedStep), orgId.toString(), wsId.toString()).decision());

        String expiredToken = signToken(TEST_INTERNAL_SECRET, "TerrakubeInternal", "TerrakubeInternal (TOKEN)", Map.of(
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString(),
                "jobId", "101",
                "stepId", stepId.toString()
        ), -120);

        assertEquals(StateService.HttpBackendAccessDecision.UNAUTHORIZED,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", expiredToken), orgId.toString(), wsId.toString()).decision());
    }

    @Test
    void testHttpBackendAccessForbiddenForMismatchedScopeOrNonWorkspaceTokens() {
        ReflectionTestUtils.setField(stateService, "internalJwtSecret", TEST_INTERNAL_SECRET);
        ReflectionTestUtils.setField(stateService, "patJwtSecret", TEST_PAT_SECRET);

        UUID stepId = UUID.randomUUID();
        io.terrakube.api.rs.job.step.Step activeStep = new io.terrakube.api.rs.job.step.Step();
        activeStep.setId(stepId);
        activeStep.setStatus(io.terrakube.api.rs.job.JobStatus.running);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(activeStep));

        String wsToken = signToken(TEST_INTERNAL_SECRET, "TerrakubeInternal", "TerrakubeInternal (TOKEN)", Map.of(
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString(),
                "jobId", "101",
                "stepId", stepId.toString()
        ), 300);

        // Mismatched workspace
        assertEquals(StateService.HttpBackendAccessDecision.FORBIDDEN,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", wsToken), orgId.toString(), UUID.randomUUID().toString()).decision());
        // Mismatched organization
        assertEquals(StateService.HttpBackendAccessDecision.FORBIDDEN,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", wsToken), UUID.randomUUID().toString(), wsId.toString()).decision());

        // EXECUTOR_SERVICE token (unscoped) must be rejected on HTTP backend
        String executorServiceToken = signToken(TEST_INTERNAL_SECRET, "TerrakubeInternal", "TerrakubeInternal (EXECUTOR)", Map.of(), 300);
        assertEquals(StateService.HttpBackendAccessDecision.FORBIDDEN,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", executorServiceToken), orgId.toString(), wsId.toString()).decision());

        // REGISTRY_SERVICE token must be rejected
        String registryServiceToken = signToken(TEST_INTERNAL_SECRET, "TerrakubeInternal", "TerrakubeInternal (REGISTRY)", Map.of(), 300);
        assertEquals(StateService.HttpBackendAccessDecision.FORBIDDEN,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", registryServiceToken), orgId.toString(), wsId.toString()).decision());

        // User PAT token must be rejected with FORBIDDEN
        String userPatToken = signToken(TEST_PAT_SECRET, "Terrakube", "user@example.com", Map.of(
                "groups", java.util.List.of("TERRAKUBE_ADMIN")
        ), 300);
        assertEquals(StateService.HttpBackendAccessDecision.FORBIDDEN,
                stateService.authorizeHttpBackendAccess(basicHeader("terraform", userPatToken), orgId.toString(), wsId.toString()).decision());
    }

    private static final String TEST_INTERNAL_SECRET = "dGVycmFrdWJlLWludGVybmFsLXNlY3JldC1rZXktZm9yLXRlc3RpbmctMjU2Yml0cw";
    private static final String TEST_PAT_SECRET = "dGVycmFrdWJlLXBhdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLTI1NmJpdHMtdG9rZW4";

    private String signToken(String base64UrlSecret, String issuer, String subject, Map<String, Object> extraClaims, long expirySecondsOffset) {
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64URL.decode(base64UrlSecret));
        java.time.Instant now = java.time.Instant.now();
        var builder = io.jsonwebtoken.Jwts.builder()
                .setIssuer(issuer)
                .setSubject(subject)
                .setIssuedAt(java.util.Date.from(now.minusSeconds(10)))
                .setExpiration(java.util.Date.from(now.plusSeconds(expirySecondsOffset)));
        if (extraClaims != null) {
            extraClaims.forEach(builder::claim);
        }
        return builder.signWith(key).compact();
    }

    private String basicHeader(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void testReadStateUserTokenDelegatesToManageState() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://dex.example.com",
                "groups", java.util.List.of("dev-team")
        ));

        io.terrakube.api.rs.team.Team team = new io.terrakube.api.rs.team.Team();
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, java.util.List.of("dev-team")))
                .thenReturn(java.util.List.of(team));
        when(rbacService.canManageState(team)).thenReturn(true);

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), wsId.toString()));
    }
}
