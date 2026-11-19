package io.terrakube.registry.controller;

import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.mirror.MirrorBusyException;
import io.terrakube.registry.service.mirror.MirrorUpstreamException;
import io.terrakube.registry.service.mirror.ProviderMirrorService;
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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProviderMirrorSecurityTest {

    private static final String BASE = "/terraform/providers/mirror/v1/my-org/registry.terraform.io/hashicorp/random";
    private static final String ARCHIVE = BASE + "/3.6.0/linux_amd64.zip";
    private static final String KEY = "mirror/registry.terraform.io/hashicorp/random/terraform-provider-random_3.6.0_linux_amd64.zip";

    @Mock
    private ProviderMirrorService mirrorService;
    @Mock
    private StorageService storageService;
    @Mock
    private ModuleAuthorizationService moduleAuthorizationService;
    @Mock
    private ModuleService moduleService;

    private final ModuleDownloadTicketService ticketService = new ModuleDownloadTicketService(
            Base64.getUrlEncoder().withoutPadding().encodeToString("my-super-secret-key-32-bytes-long!".getBytes()), 300);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProviderMirrorWebServiceImpl(mirrorService, storageService, ticketService, moduleAuthorizationService))
                .setControllerAdvice(new StorageExceptionHandler())
                .build();
    }

    @Test
    void unauthenticatedGets403OnJsonEndpoints() throws Exception {
        when(moduleAuthorizationService.isAuthorized(null, "my-org")).thenReturn(false);

        mockMvc.perform(get(BASE + "/index.json")).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/3.6.0.json")).andExpect(status().isForbidden());
        verify(mirrorService, never()).index(anyString(), anyString(), anyString());
    }

    @Test
    void unauthorizedOrgGets403OnIndex() throws Exception {
        Authentication auth = jwt("other-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(false);

        mockMvc.perform(get(BASE + "/index.json").principal(auth)).andExpect(status().isForbidden());
    }

    @Test
    void authorizedMemberGetsIndexAndVersion() throws Exception {
        Authentication auth = jwt("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        when(mirrorService.index("registry.terraform.io", "hashicorp", "random")).thenReturn(Optional.of("{\"versions\":{\"3.6.0\":{}}}"));
        when(mirrorService.version("my-org", "registry.terraform.io", "hashicorp", "random", "3.6.0"))
                .thenReturn(Optional.of("{\"archives\":{}}"));

        mockMvc.perform(get(BASE + "/index.json").principal(auth))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"versions\":{\"3.6.0\":{}}}"));
        mockMvc.perform(get(BASE + "/3.6.0.json").principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"archives\":{}}"));
    }

    @Test
    void unknownProviderIs404() throws Exception {
        Authentication auth = jwt("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        when(mirrorService.index(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/index.json").principal(auth)).andExpect(status().isNotFound());
    }

    @Test
    void invalidPathPartsAre400() throws Exception {
        mockMvc.perform(get("/terraform/providers/mirror/v1/my-org/Bad_Host/hashicorp/random/index.json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/1..0.json")).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/3.6.0/linux-amd64.zip")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidTicketGets403OnArchive() throws Exception {
        mockMvc.perform(get(ARCHIVE).param("ticket", "123.abcd")).andExpect(status().isForbidden());
        verify(mirrorService, never()).archiveKey(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ticketForAnotherPlatformGets403() throws Exception {
        String ticket = ticketService.generateMirrorTicket("my-org", "registry.terraform.io", "hashicorp", "random", "3.6.0", "darwin_arm64");

        mockMvc.perform(get(ARCHIVE).param("ticket", ticket)).andExpect(status().isForbidden());
    }

    @Test
    void moduleTicketIsRejectedOnMirrorArchive() throws Exception {
        String ticket = ticketService.generateTicket("my-org", "registry.terraform.io", "hashicorp", "random");

        mockMvc.perform(get(ARCHIVE).param("ticket", ticket)).andExpect(status().isForbidden());
    }

    @Test
    void mirrorTicketIsRejectedOnModuleZip() throws Exception {
        MockMvc modules = MockMvcBuilders.standaloneSetup(
                new ModuleWebServiceImpl(moduleService, storageService, ticketService, moduleAuthorizationService)).build();
        String ticket = ticketService.generateMirrorTicket("my-org", "registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64");

        modules.perform(get("/terraform/modules/v1/download/my-org/hashicorp/random/3.6.0/module.zip").param("ticket", ticket))
                .andExpect(status().isForbidden());
    }

    @Test
    void validTicketStreamsArchive() throws Exception {
        String ticket = ticketService.generateMirrorTicket("my-org", "registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64");
        when(mirrorService.archiveKey("registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64")).thenReturn(Optional.of(KEY));
        when(storageService.presignObject(KEY)).thenReturn(Optional.empty());
        when(storageService.readObject(KEY)).thenReturn(Optional.of(new ByteArrayInputStream("zip".getBytes())));

        mockMvc.perform(get(ARCHIVE).param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes("zip".getBytes()));
    }

    @Test
    void authorizedBearerWithoutTicketGetsArchive() throws Exception {
        Authentication auth = jwt("my-org-team");
        when(moduleAuthorizationService.isAuthorized(auth, "my-org")).thenReturn(true);
        when(mirrorService.archiveKey("registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64")).thenReturn(Optional.of(KEY));
        URI presigned = URI.create("https://storage.example.com/" + KEY + "?sig=1");
        when(storageService.presignObject(KEY)).thenReturn(Optional.of(presigned));

        mockMvc.perform(get(ARCHIVE).principal(auth))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", presigned.toString()));
    }

    @Test
    void noTicketAndNoMembershipGets403OnArchive() throws Exception {
        when(moduleAuthorizationService.isAuthorized(any(), any())).thenReturn(false);

        mockMvc.perform(get(ARCHIVE)).andExpect(status().isForbidden());
    }

    @Test
    void missingArchiveIs404AndUpstreamFailureIs502() throws Exception {
        String ticket = ticketService.generateMirrorTicket("my-org", "registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64");
        when(mirrorService.archiveKey("registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64"))
                .thenReturn(Optional.empty())
                .thenThrow(new MirrorUpstreamException("Checksum mismatch"));

        mockMvc.perform(get(ARCHIVE).param("ticket", ticket)).andExpect(status().isNotFound());
        mockMvc.perform(get(ARCHIVE).param("ticket", ticket)).andExpect(status().isBadGateway());
    }

    @Test
    void busyFetchSlotsAre503WithRetryAfter() throws Exception {
        String ticket = ticketService.generateMirrorTicket("my-org", "registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64");
        when(mirrorService.archiveKey("registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64"))
                .thenThrow(new MirrorBusyException("All 4 pull-through fetch slots are busy"));

        mockMvc.perform(get(ARCHIVE).param("ticket", ticket))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"));
    }

    private static Authentication jwt(String group) {
        Jwt jwt = new Jwt("mock-token-value", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "HS256"),
                Map.of("sub", "user1", "groups", List.of(group), "iss", "https://dex.example.com"));
        return new JwtAuthenticationToken(jwt);
    }
}
