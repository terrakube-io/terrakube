package io.terrakube.registry.service.module;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.ClaimsData;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.FederatedAttributes;
import io.terrakube.client.model.federated.Relationships;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.federated.claim.FederatedClaimAttributes;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.WorkspaceData;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.job.step.StepAttributes;
import io.terrakube.client.model.organization.team.Team;
import io.terrakube.client.model.organization.team.TeamAttributes;
import io.terrakube.client.model.organization.workspace.Workspace;
import io.terrakube.client.model.organization.workspace.access.Access;
import io.terrakube.client.model.organization.workspace.access.AccessAttributes;
import io.terrakube.client.model.response.Response;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.client.security.TerrakubeCallerClassifier;
import io.terrakube.registry.service.search.CommonSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModuleAuthorizationServiceTest {

    private static final String OIDC_ISSUER = "https://dex.example.com";
    private static final String GITHUB_ISSUER = "https://token.actions.githubusercontent.com";
    private static final String ORG_NAME = "my-org";
    private static final String OTHER_ORG_NAME = "other-org";
    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG_ID = "22222222-2222-2222-2222-222222222222";
    private static final String WS_ID = "33333333-3333-3333-3333-333333333333";
    private static final String OTHER_WS_ID = "55555555-5555-5555-5555-555555555555";
    private static final String STEP_ID = "44444444-4444-4444-4444-444444444444";

    @Mock
    private CommonSearchService commonSearchService;

    @Mock
    private TerrakubeClient terrakubeClient;

    private ModuleAuthorizationService authService;

    @BeforeEach
    void setUp() {
        authService = new ModuleAuthorizationService(
                commonSearchService,
                terrakubeClient,
                OIDC_ISSUER,
                "super-admin-group",
                "DEX"
        );
        lenient().when(commonSearchService.getOrganizationId(ORG_NAME)).thenReturn(ORG_ID);
        lenient().when(commonSearchService.getOrganizationId(OTHER_ORG_NAME)).thenReturn(OTHER_ORG_ID);
    }

    @Test
    void testValidateConfiguration_BlankIssuerFailsFastInDexModeAndAllowsLocalMode() {
        ModuleAuthorizationService blankDex = new ModuleAuthorizationService(
                commonSearchService, terrakubeClient, "", "super-admin-group", "DEX");
        assertThrows(IllegalStateException.class, blankDex::validateConfiguration);

        ModuleAuthorizationService nullDex = new ModuleAuthorizationService(
                commonSearchService, terrakubeClient, null, "super-admin-group", "DEX");
        assertThrows(IllegalStateException.class, nullDex::validateConfiguration);

        ModuleAuthorizationService blankLocal = new ModuleAuthorizationService(
                commonSearchService, terrakubeClient, "", "super-admin-group", "LOCAL");
        assertDoesNotThrow(blankLocal::validateConfiguration);
        assertDoesNotThrow(authService::validateConfiguration);
    }

    @Test
    void testIsAuthorized_NullOrInvalidOrgNames_RejectWithoutGraphQlLookup() {
        assertFalse(authService.isAuthorized(null, ORG_NAME));
        JwtAuthenticationToken token = jwtToken(Map.of("iss", OIDC_ISSUER, "groups", List.of("super-admin-group")));
        assertFalse(authService.isAuthorized(token, ""));
        assertFalse(authService.isAuthorized(token, null));

        for (String injection : List.of("simple,name==aws", "simple*", "simple\"", "simple%2Cname==aws", "simple;name==aws")) {
            assertFalse(authService.isAuthorized(token, injection), "Expected injection payload rejected: " + injection);
        }
        verifyNoInteractions(commonSearchService);
    }

    @Test
    void testIsAuthorized_InternalServiceTokens() {
        JwtAuthenticationToken registryService = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
        assertTrue(authService.isAuthorized(registryService, ORG_NAME));

        JwtAuthenticationToken executorService = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));
        assertFalse(authService.isAuthorized(executorService, ORG_NAME));

        JwtAuthenticationToken unknownInternal = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));
        assertFalse(authService.isAuthorized(unknownInternal, ORG_NAME));
        verifyNoInteractions(commonSearchService);
    }

    @Test
    void testIsAuthorized_WorkspaceExecutor_ActiveJobAndStepStatuses_AreAuthorizedAndUncached() {
        when(terrakubeClient.getJobById(ORG_ID, "101"))
                .thenReturn(jobResponse("running", WS_ID, "running"))
                .thenReturn(jobResponse("running", WS_ID, "approved"))
                .thenReturn(jobResponse("running", WS_ID, "waitingApproval"))
                .thenReturn(jobResponse("running", WS_ID, "completed"));

        JwtAuthenticationToken runner = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)",
                "organizationId", ORG_ID,
                "workspaceId", WS_ID,
                "jobId", "101",
                "stepId", STEP_ID,
                "email", "super-admin-group"
        ));

        assertTrue(authService.isAuthorized(runner, ORG_NAME));
        assertTrue(authService.isAuthorized(runner, ORG_NAME));
        assertTrue(authService.isAuthorized(runner, ORG_NAME));
        // Step transitions to completed: next call must fail immediately without stale caching
        assertFalse(authService.isAuthorized(runner, ORG_NAME));
        verify(terrakubeClient, times(4)).getJobById(ORG_ID, "101");
    }

    @Test
    void testIsAuthorized_WorkspaceExecutor_JtiFallback_StrictUuidRequired() {
        when(terrakubeClient.getJobById(ORG_ID, "101"))
                .thenReturn(jobResponse("running", WS_ID, "running"));

        JwtAuthenticationToken validJtiFallback = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)",
                "organizationId", ORG_ID,
                "workspaceId", WS_ID,
                "jti", "101-" + STEP_ID
        ));
        assertTrue(authService.isAuthorized(validJtiFallback, ORG_NAME));

        JwtAuthenticationToken looseNonUuidJti = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)",
                "organizationId", ORG_ID,
                "workspaceId", WS_ID,
                "jti", "101-not-a-uuid"
        ));
        assertFalse(authService.isAuthorized(looseNonUuidJti, ORG_NAME));
    }

    @Test
    void testIsAuthorized_WorkspaceExecutor_TerminalJobOrStepOrWrongWorkspace_ReturnsFalse() {
        JwtAuthenticationToken runner = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)",
                "organizationId", ORG_ID,
                "workspaceId", WS_ID,
                "jobId", "101",
                "stepId", STEP_ID
        ));

        // Cross-org denied without calling getJobById
        assertFalse(authService.isAuthorized(runner, OTHER_ORG_NAME));

        // Missing organizationId denied
        JwtAuthenticationToken missingOrgRunner = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)",
                "workspaceId", WS_ID,
                "jobId", "101",
                "stepId", STEP_ID
        ));
        assertFalse(authService.isAuthorized(missingOrgRunner, ORG_NAME));

        // Terminal step statuses (failed, cancelled)
        when(terrakubeClient.getJobById(ORG_ID, "101"))
                .thenReturn(jobResponse("running", WS_ID, "failed"))
                .thenReturn(jobResponse("running", WS_ID, "cancelled"))
                // Terminal job statuses even while step still reads running
                .thenReturn(jobResponse("cancelled", WS_ID, "running"))
                .thenReturn(jobResponse("failed", WS_ID, "running"))
                // Same org, different workspaceId
                .thenReturn(jobResponse("running", OTHER_WS_ID, "running"));

        assertFalse(authService.isAuthorized(runner, ORG_NAME));
        assertFalse(authService.isAuthorized(runner, ORG_NAME));
        assertFalse(authService.isAuthorized(runner, ORG_NAME));
        assertFalse(authService.isAuthorized(runner, ORG_NAME));
        assertFalse(authService.isAuthorized(runner, ORG_NAME));
    }

    @Test
    void testIsAuthorized_FederatedOidc_AuthorizedViaTeamOrWorkspaceAccessOnly() {
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(GITHUB_ISSUER, "terrakube"))
                .thenReturn(federatedResponse("github-pipeline-team", "repository", "acme/infra"));
        when(terrakubeClient.getAllTeams(ORG_ID)).thenReturn(teamsResponse("github-pipeline-team"));
        when(terrakubeClient.getAllTeams(OTHER_ORG_ID)).thenReturn(teamsResponse("other-team"));
        when(terrakubeClient.getWorkspacesWithAccess(OTHER_ORG_ID)).thenReturn(accessResponse("other-ws-team"));

        JwtAuthenticationToken validFederated = jwtToken(Map.of(
                "iss", GITHUB_ISSUER,
                "aud", List.of("terrakube"),
                "repository", "acme/infra",
                "email", "super-admin-group",
                "groups", List.of("other-team", "super-admin-group")
        ));

        assertTrue(authService.isAuthorized(validFederated, ORG_NAME));
        assertFalse(authService.isAuthorized(validFederated, OTHER_ORG_NAME));

        // Fresh service instance for workspace Access check (no Team membership)
        ModuleAuthorizationService freshAuthService = new ModuleAuthorizationService(
                commonSearchService, terrakubeClient, OIDC_ISSUER, "super-admin-group", "DEX");
        when(terrakubeClient.getAllTeams(ORG_ID)).thenReturn(teamsResponse());
        when(terrakubeClient.getWorkspacesWithAccess(ORG_ID)).thenReturn(accessResponse("github-pipeline-team"));
        assertTrue(freshAuthService.isAuthorized(validFederated, ORG_NAME));

        JwtAuthenticationToken wrongClaimFederated = jwtToken(Map.of(
                "iss", GITHUB_ISSUER,
                "aud", List.of("terrakube"),
                "repository", "evil/repo",
                "email", "super-admin-group",
                "groups", List.of("github-pipeline-team")
        ));
        assertFalse(freshAuthService.isAuthorized(wrongClaimFederated, ORG_NAME));
    }

    @Test
    void testIsAuthorized_OidcUserAndPatOrTeamToken_AndShortTtlCaching() {
        when(terrakubeClient.getAllTeams(ORG_ID)).thenReturn(teamsResponse("dev-team", "ops-team"));
        when(terrakubeClient.getWorkspacesWithAccess(ORG_ID)).thenReturn(accessResponse("ws-limited"));

        JwtAuthenticationToken ownerUser = jwtToken(Map.of(
                "iss", OIDC_ISSUER,
                "groups", List.of("super-admin-group")
        ));
        assertTrue(authService.isAuthorized(ownerUser, ORG_NAME));

        JwtAuthenticationToken teamUser = jwtToken(Map.of(
                "iss", OIDC_ISSUER,
                "groups", List.of("dev-team")
        ));
        assertTrue(authService.isAuthorized(teamUser, ORG_NAME));

        JwtAuthenticationToken patOwner = jwtToken(Map.of(
                "iss", "Terrakube",
                "sub", "admin (PAT)",
                "email", "super-admin-group",
                "groups", List.of()
        ));
        assertTrue(authService.isAuthorized(patOwner, ORG_NAME));

        JwtAuthenticationToken teamTokenViaTeam = jwtToken(Map.of(
                "iss", "Terrakube",
                "sub", "ops-team (Team Token)",
                "groups", List.of("ops-team")
        ));
        assertTrue(authService.isAuthorized(teamTokenViaTeam, ORG_NAME));

        JwtAuthenticationToken teamTokenViaAccess = jwtToken(Map.of(
                "iss", "Terrakube",
                "sub", "ws-limited (Team Token)",
                "groups", List.of("ws-limited")
        ));
        assertTrue(authService.isAuthorized(teamTokenViaAccess, ORG_NAME));

        JwtAuthenticationToken nonMemberPat = jwtToken(Map.of(
                "iss", "Terrakube",
                "sub", "stranger (Team Token)",
                "groups", List.of("other-team")
        ));
        assertFalse(authService.isAuthorized(nonMemberPat, ORG_NAME));

        // Repeated calls share 60s org/teams/access caches instead of hitting API on every request
        verify(commonSearchService, times(1)).getOrganizationId(ORG_NAME);
        verify(terrakubeClient, times(1)).getAllTeams(ORG_ID);
        verify(terrakubeClient, times(1)).getWorkspacesWithAccess(ORG_ID);

        when(commonSearchService.getOrganizationId("unknown-org")).thenReturn(null);
        assertFalse(authService.isAuthorized(ownerUser, "unknown-org"));
    }

    @Test
    void testActiveStepStatusesSingleSource_AndProxyUnwrapsInvocationTargetException() {
        assertEquals(
                Set.of("running", "queue", "pending", "waitingApproval", "approved"),
                TerrakubeCallerClassifier.ACTIVE_STEP_STATUSES
        );

        when(terrakubeClient.getJobById(ORG_ID, "101"))
                .thenThrow(new IllegalStateException("downstream-api-failure"));

        TerrakubeClient cachedProxy = (TerrakubeClient) ReflectionTestUtils.getField(authService, "cachedLookupClient");
        IllegalStateException unwrapped = assertThrows(
                IllegalStateException.class,
                () -> cachedProxy.getJobById(ORG_ID, "101")
        );
        assertEquals("downstream-api-failure", unwrapped.getMessage());

        JwtAuthenticationToken runner = jwtToken(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)",
                "organizationId", ORG_ID,
                "workspaceId", WS_ID,
                "jobId", "101",
                "stepId", STEP_ID
        ));
        assertFalse(authService.isAuthorized(runner, ORG_NAME));
    }

    private JwtAuthenticationToken jwtToken(Map<String, Object> claims) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                claims
        );
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private ResponseWithInclude<Job, Step> jobResponse(String jobStatus, String workspaceId, String stepStatus) {
        Job job = new Job();
        job.setId("101");
        job.setType("job");
        JobAttributes jobAttrs = new JobAttributes();
        jobAttrs.setStatus(jobStatus);
        job.setAttributes(jobAttrs);

        Resource wsRes = new Resource();
        wsRes.setId(workspaceId);
        wsRes.setType("workspace");
        WorkspaceData wsData = new WorkspaceData();
        wsData.setData(wsRes);
        io.terrakube.client.model.organization.job.Relationships jobRels =
                new io.terrakube.client.model.organization.job.Relationships();
        jobRels.setWorkspace(wsData);
        job.setRelationships(jobRels);

        Step step = new Step();
        step.setId(STEP_ID);
        step.setType("step");
        StepAttributes stepAttrs = new StepAttributes();
        stepAttrs.setStatus(stepStatus);
        step.setAttributes(stepAttrs);

        ResponseWithInclude<Job, Step> response = new ResponseWithInclude<>();
        response.setData(job);
        response.setIncluded(List.of(step));
        return response;
    }

    private Response<List<Team>> teamsResponse(String... teamNames) {
        List<Team> teams = java.util.Arrays.stream(teamNames)
                .map(name -> {
                    Team team = new Team();
                    team.setId(UUID.randomUUID().toString());
                    team.setType("team");
                    TeamAttributes attrs = new TeamAttributes();
                    attrs.setName(name);
                    team.setAttributes(attrs);
                    return team;
                })
                .toList();
        Response<List<Team>> response = new Response<>();
        response.setData(teams);
        return response;
    }

    private ResponseWithInclude<List<Workspace>, Access> accessResponse(String... accessNames) {
        List<Access> included = java.util.Arrays.stream(accessNames)
                .map(name -> {
                    Access access = new Access();
                    access.setId(UUID.randomUUID().toString());
                    access.setType("access");
                    AccessAttributes attrs = new AccessAttributes();
                    attrs.setName(name);
                    access.setAttributes(attrs);
                    return access;
                })
                .toList();
        ResponseWithInclude<List<Workspace>, Access> response = new ResponseWithInclude<>();
        response.setData(List.of());
        response.setIncluded(included);
        return response;
    }

    private ResponseWithInclude<List<Federated>, FederatedClaim> federatedResponse(
            String name, String claimKey, String claimValue) {
        FederatedClaim claim = new FederatedClaim();
        claim.setId("claim-1");
        claim.setType("federated_claim");
        FederatedClaimAttributes claimAttrs = new FederatedClaimAttributes();
        claimAttrs.setClaimKey(claimKey);
        claimAttrs.setClaimValue(claimValue);
        claim.setAttributes(claimAttrs);

        Federated federated = new Federated();
        federated.setId("fed-1");
        federated.setType("federated");
        FederatedAttributes fedAttrs = new FederatedAttributes();
        fedAttrs.setName(name);
        fedAttrs.setIssuerUrl(GITHUB_ISSUER);
        fedAttrs.setAudience("terrakube");
        federated.setAttributes(fedAttrs);

        ClaimsData claimsData = new ClaimsData();
        claimsData.setData(List.of((Resource) claim));
        Relationships rels = new Relationships();
        rels.setClaims(claimsData);
        federated.setRelationships(rels);

        ResponseWithInclude<List<Federated>, FederatedClaim> response = new ResponseWithInclude<>();
        response.setData(List.of(federated));
        response.setIncluded(List.of(claim));
        return response;
    }
}
