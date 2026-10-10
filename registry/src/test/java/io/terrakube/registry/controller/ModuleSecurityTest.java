package io.terrakube.registry.controller;

import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleAuthorizationService;
import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import io.terrakube.registry.service.module.ModuleService;
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
