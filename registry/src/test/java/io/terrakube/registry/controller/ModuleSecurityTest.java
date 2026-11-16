package io.terrakube.registry.controller;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.ClaimsData;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.FederatedAttributes;
import io.terrakube.client.model.federated.Relationships;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.federated.claim.FederatedClaimAttributes;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.job.step.StepAttributes;
import io.terrakube.client.model.organization.team.Team;
import io.terrakube.client.model.organization.team.TeamAttributes;
import io.terrakube.client.model.response.Response;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleAuthorizationService;
import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import io.terrakube.registry.service.module.ModuleService;
import io.terrakube.registry.service.search.CommonSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModuleSecurityTest {

    @Mock
    private ModuleService moduleService;

    @Mock
    private StorageService storageService;

    @Mock
    private ModuleAuthorizationService moduleAuthorizationService;

    @Mock
    private io.terrakube.registry.service.ReadMeServiceImpl readMeService;

    private ModuleDownloadTicketService ticketService;
    private MockMvc mockMvc;
    private MockMvc readMeMockMvc;

    private final String secret = Base64.getUrlEncoder().withoutPadding().encodeToString("my-super-secret-key-32-bytes-long!".getBytes());

    @BeforeEach
    void setUp() {
        ticketService = new ModuleDownloadTicketService(secret, 300);

        ModuleWebServiceImpl controller = new ModuleWebServiceImpl(
                moduleService,
                storageService,
                ticketService,
                moduleAuthorizationService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StorageExceptionHandler())
                .build();

        ReadMeWebServiceImpl readMeController = new ReadMeWebServiceImpl(
                moduleService,
                readMeService,
                storageService,
                moduleAuthorizationService
        );

        readMeMockMvc = MockMvcBuilders.standaloneSetup(readMeController).build();
    }

    @Test
    void testDownloadZip_UnauthenticatedWithoutTicket_ReturnsForbidden() throws Exception {
        when(moduleAuthorizationService.isAuthorized(null, "my-org")).thenReturn(false);

        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testDownloadZip_ValidTicket_ReturnsSuccess() throws Exception {
        String ticket = ticketService.generateTicket("my-org", "vpc", "aws", "1.0.0");
        byte[] zipBytes = "zip-data".getBytes();
        when(storageService.getPresignedDownloadUrl("my-org", "vpc", "aws", "1.0.0")).thenReturn(Optional.empty());
        when(storageService.downloadModule("my-org", "vpc", "aws", "1.0.0")).thenReturn(zipBytes);

        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip")
                        .param("ticket", ticket))
                .andExpect(status().isOk());
    }

    @Test
    void testDownloadZip_ValidTicketPresignedUrl_ReturnsFoundRedirect() throws Exception {
        String ticket = ticketService.generateTicket("my-org", "vpc", "aws", "1.0.0");
        URI presigned = URI.create("https://storage.example.com/my-org/vpc/aws/1.0.0/module.zip");
        when(storageService.getPresignedDownloadUrl("my-org", "vpc", "aws", "1.0.0")).thenReturn(Optional.of(presigned));

        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip")
                        .param("ticket", ticket))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", presigned.toString()));
    }

    @Test
    void testDownloadZip_TamperedVersionInTicket_ReturnsForbidden() throws Exception {
        // Ticket was generated for version 1.0.0
        String ticket = ticketService.generateTicket("my-org", "vpc", "aws", "1.0.0");

        // Attacker attempts to use it to download version 2.0.0
        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/2.0.0/module.zip")
                        .param("ticket", ticket))
                .andExpect(status().isForbidden());
    }

    @Test
    void testDownloadZip_ExpiredTicket_ReturnsForbidden() throws Exception {
        ModuleDownloadTicketService expiredTicketService = new ModuleDownloadTicketService(secret, -60);
        String expiredTicket = expiredTicketService.generateTicket("my-org", "vpc", "aws", "1.0.0");

        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip")
                        .param("ticket", expiredTicket))
                .andExpect(status().isForbidden());
    }

    @Test
    void testDownloadZip_DirectDownloadWithOrgMembership_ReturnsSuccess() throws Exception {
        Authentication auth = createJwtToken("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        byte[] zipBytes = "zip-data".getBytes();
        when(storageService.getPresignedDownloadUrl("my-org", "vpc", "aws", "1.0.0")).thenReturn(Optional.empty());
        when(storageService.downloadModule("my-org", "vpc", "aws", "1.0.0")).thenReturn(zipBytes);

        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip")
                        .principal(auth))
                .andExpect(status().isOk());
    }

    @Test
    void testDownloadZip_DirectDownloadWithoutOrgMembership_ReturnsForbidden() throws Exception {
        Authentication auth = createJwtToken("other-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(false);

        mockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip")
                        .principal(auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void testSearchVersions_UnauthorizedUser_ReturnsForbidden() throws Exception {
        Authentication auth = createJwtToken("other-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(false);

        mockMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/versions")
                        .principal(auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void testSearchVersions_AuthorizedUser_ReturnsSuccess() throws Exception {
        Authentication auth = createJwtToken("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        when(moduleService.getAvailableVersions("my-org", "vpc", "aws")).thenReturn(List.of("1.0.0", "1.1.0"));

        mockMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/versions")
                        .principal(auth))
                .andExpect(status().isOk());
    }

    @Test
    void testGetModuleVersionPath_AppendsTicketToXTerraformGet() throws Exception {
        Authentication auth = createJwtToken("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        when(moduleService.getModuleVersionPath("my-org", "vpc", "aws", "1.0.0"))
                .thenReturn("https://registry.example.com/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip");

        mockMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/1.0.0/download")
                        .principal(auth))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Terraform-Get"));
    }

    @Test
    void testReadMe_UnauthorizedUser_ReturnsForbidden() throws Exception {
        Authentication auth = createJwtToken("other-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(false);

        readMeMockMvc.perform(get("/terraform/readme/v1/my-org/vpc/aws/1.0.0/download")
                        .principal(auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void testReadMe_AuthorizedUser_ReturnsSuccess() throws Exception {
        Authentication auth = createJwtToken("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        when(moduleService.isVersionRemoved("my-org", "vpc", "aws", "1.0.0")).thenReturn(false);
        when(moduleService.getModuleVersionPath("my-org", "vpc", "aws", "1.0.0")).thenReturn("https://example.com");
        when(storageService.downloadModule("my-org", "vpc", "aws", "1.0.0")).thenReturn("zip-content".getBytes());
        when(readMeService.getContent(any())).thenReturn("# Readme content");

        readMeMockMvc.perform(get("/terraform/readme/v1/my-org/vpc/aws/1.0.0/download")
                        .principal(auth))
                .andExpect(status().isOk());
    }

    @Test
    void testModuleEndpoints_NullAuthorizationService_FailsClosed() throws Exception {
        ModuleWebServiceImpl nullAuthController = new ModuleWebServiceImpl(
                moduleService,
                storageService,
                ticketService,
                null
        );
        MockMvc nullAuthMockMvc = MockMvcBuilders.standaloneSetup(nullAuthController).build();
        Authentication auth = createJwtToken("any-team");

        nullAuthMockMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/versions").principal(auth))
                .andExpect(status().isForbidden());

        nullAuthMockMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/1.0.0/download").principal(auth))
                .andExpect(status().isForbidden());

        nullAuthMockMvc.perform(get("/terraform/modules/v1/download/my-org/vpc/aws/1.0.0/module.zip").principal(auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void testRealAuthorization_RunnerTokenAndFederatedOidc_EnforceOrgAndStepScope() throws Exception {
        CommonSearchService searchService = org.mockito.Mockito.mock(CommonSearchService.class);
        TerrakubeClient terrakubeClient = org.mockito.Mockito.mock(TerrakubeClient.class);
        ModuleAuthorizationService realAuthService = new ModuleAuthorizationService(
                searchService,
                terrakubeClient,
                "https://dex.example.com",
                "owner@example.com",
                "DEX"
        );
        MockMvc realAuthModuleMvc = MockMvcBuilders.standaloneSetup(
                new ModuleWebServiceImpl(moduleService, storageService, ticketService, realAuthService)
        ).build();
        MockMvc realAuthReadMeMvc = MockMvcBuilders.standaloneSetup(
                new ReadMeWebServiceImpl(moduleService, readMeService, storageService, realAuthService)
        ).build();

        String myOrgId = "11111111-1111-1111-1111-111111111111";
        String otherOrgId = "22222222-2222-2222-2222-222222222222";
        String stepId = "44444444-4444-4444-4444-444444444444";
        when(searchService.getOrganizationId("my-org")).thenReturn(myOrgId);
        when(searchService.getOrganizationId("other-org")).thenReturn(otherOrgId);
        when(moduleService.getAvailableVersions("my-org", "vpc", "aws")).thenReturn(List.of("1.0.0"));
        when(moduleService.isVersionRemoved("my-org", "vpc", "aws", "1.0.0")).thenReturn(false);
        when(moduleService.getModuleVersionPath("my-org", "vpc", "aws", "1.0.0")).thenReturn("https://example.com");
        when(storageService.downloadModule("my-org", "vpc", "aws", "1.0.0")).thenReturn("zip".getBytes());
        when(readMeService.getContent(any())).thenReturn("# Readme");

        Step activeStep = new Step();
        activeStep.setId(stepId);
        StepAttributes activeAttrs = new StepAttributes();
        activeAttrs.setStatus("running");
        activeStep.setAttributes(activeAttrs);
        Response<Step> activeStepResp = new Response<>();
        activeStepResp.setData(activeStep);

        Step completedStep = new Step();
        completedStep.setId(stepId);
        StepAttributes completedAttrs = new StepAttributes();
        completedAttrs.setStatus("completed");
        completedStep.setAttributes(completedAttrs);
        Response<Step> completedStepResp = new Response<>();
        completedStepResp.setData(completedStep);

        when(terrakubeClient.getStepById(myOrgId, "101", stepId))
                .thenReturn(activeStepResp)
                .thenReturn(completedStepResp);

        Authentication runnerAuth = new JwtAuthenticationToken(new Jwt(
                "runner-jwt",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of(
                        "iss", "TerrakubeInternal",
                        "sub", "TerrakubeInternal (EXECUTOR)",
                        "organizationId", myOrgId,
                        "workspaceId", "33333333-3333-3333-3333-333333333333",
                        "jobId", "101",
                        "stepId", stepId
                )
        ));

        // Active runner token allowed on own org, denied on other-org, then denied once step completes
        realAuthModuleMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/versions").principal(runnerAuth))
                .andExpect(status().isOk());
        realAuthModuleMvc.perform(get("/terraform/modules/v1/other-org/vpc/aws/versions").principal(runnerAuth))
                .andExpect(status().isForbidden());
        realAuthReadMeMvc.perform(get("/terraform/readme/v1/other-org/vpc/aws/1.0.0/download").principal(runnerAuth))
                .andExpect(status().isForbidden());
        realAuthModuleMvc.perform(get("/terraform/modules/v1/my-org/vpc/aws/versions").principal(runnerAuth))
                .andExpect(status().isForbidden());

        // Federated OIDC allowed only in org where Federated.name is a Team
        FederatedClaim fedClaim = new FederatedClaim();
        fedClaim.setId("claim-1");
        FederatedClaimAttributes fedClaimAttrs = new FederatedClaimAttributes();
        fedClaimAttrs.setClaimKey("repository");
        fedClaimAttrs.setClaimValue("acme/infra");
        fedClaim.setAttributes(fedClaimAttrs);
        Federated fed = new Federated();
        fed.setId("fed-1");
        FederatedAttributes fedAttrs = new FederatedAttributes();
        fedAttrs.setName("github-pipeline-team");
        fedAttrs.setIssuerUrl("https://token.actions.githubusercontent.com");
        fedAttrs.setAudience("terrakube");
        fed.setAttributes(fedAttrs);
        ClaimsData claimsData = new ClaimsData();
        claimsData.setData(List.of((Resource) fedClaim));
        Relationships fedRels = new Relationships();
        fedRels.setClaims(claimsData);
        fed.setRelationships(fedRels);
        ResponseWithInclude<List<Federated>, FederatedClaim> fedResp = new ResponseWithInclude<>();
        fedResp.setData(List.of(fed));
        fedResp.setIncluded(List.of(fedClaim));
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(
                "https://token.actions.githubusercontent.com", "terrakube")).thenReturn(fedResp);

        Team team = new Team();
        team.setId("team-1");
        TeamAttributes teamAttrs = new TeamAttributes();
        teamAttrs.setName("github-pipeline-team");
        team.setAttributes(teamAttrs);
        Response<List<Team>> myOrgTeams = new Response<>();
        myOrgTeams.setData(List.of(team));
        Response<List<Team>> otherOrgTeams = new Response<>();
        otherOrgTeams.setData(List.of());
        ResponseWithInclude<List<io.terrakube.client.model.organization.workspace.Workspace>, io.terrakube.client.model.organization.workspace.access.Access> emptyAccess = new ResponseWithInclude<>();
        emptyAccess.setData(List.of());
        emptyAccess.setIncluded(List.of());

        when(terrakubeClient.getAllTeams(myOrgId)).thenReturn(myOrgTeams);
        when(terrakubeClient.getAllTeams(otherOrgId)).thenReturn(otherOrgTeams);
        when(terrakubeClient.getWorkspacesWithAccess(otherOrgId)).thenReturn(emptyAccess);

        Authentication fedAuth = new JwtAuthenticationToken(new Jwt(
                "fed-jwt",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of(
                        "iss", "https://token.actions.githubusercontent.com",
                        "aud", List.of("terrakube"),
                        "repository", "acme/infra",
                        "email", "owner@example.com",
                        "groups", List.of("TERRAKUBE_ADMIN")
                )
        ));

        realAuthReadMeMvc.perform(get("/terraform/readme/v1/my-org/vpc/aws/1.0.0/download").principal(fedAuth))
                .andExpect(status().isOk());
        realAuthModuleMvc.perform(get("/terraform/modules/v1/other-org/vpc/aws/versions").principal(fedAuth))
                .andExpect(status().isForbidden());
        realAuthReadMeMvc.perform(get("/terraform/readme/v1/other-org/vpc/aws/1.0.0/download").principal(fedAuth))
                .andExpect(status().isForbidden());
    }


    private Authentication createJwtToken(String group) {
        Jwt jwt = new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", "user1", "groups", List.of(group), "iss", "https://dex.example.com")
        );
        return new JwtAuthenticationToken(jwt);
    }
}
