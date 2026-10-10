package io.terrakube.api.plugin.importer.tfcloud;

import io.terrakube.api.plugin.importer.tfcloud.controller.TfCloudController;
import io.terrakube.api.plugin.importer.tfcloud.services.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TfCloudUrlSecurityTest {

    @Mock
    private WorkspaceService workspaceService;

    private TfCloudController controller;

    @BeforeEach
    void setUp() {
        controller = new TfCloudController(workspaceService);
        ReflectionTestUtils.setField(controller, "allowedUrls", "https://app.terraform.io,https://custom.tfe.company.com/api/v2");
    }

    @Test
    void testValidAllowedUrlSucceeds() {
        when(workspaceService.getWorkspaces(eq("dummy-token"), eq("https://app.terraform.io"), eq("my-org")))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("https://app.terraform.io", "dummy-token", "my-org");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testPrefixMatchBypassWithUserInfoIsBlocked() {
        // Attack: https://app.terraform.io@10.75.10.108:9097 starts with https://app.terraform.io
        // But userinfo points host to 10.75.10.108
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("https://app.terraform.io@10.75.10.108:9097", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void testPrefixMatchBypassWithSubdomainIsBlocked() {
        // Attack: https://app.terraform.io.attacker.com starts with https://app.terraform.io
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("https://app.terraform.io.attacker.com", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testDisallowedExternalUrlIsBlocked() {
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("https://evil-site.com", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testCloudMetadataIpIsBlocked() {
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("https://169.254.169.254/latest/meta-data", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testLoopbackIsBlocked() {
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("http://127.0.0.1:8080", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testInvalidSchemeIsBlocked() {
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("ftp://app.terraform.io", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testNonMatchingPortIsBlocked() {
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> response =
                controller.getWorkspaces("https://app.terraform.io:8443", "dummy-token", "my-org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testPathPrefixMatchingOnCustomAllowedUrl() {
        ReflectionTestUtils.setField(controller, "allowedUrls", "https://app.terraform.io/api/v2");

        when(workspaceService.getWorkspaceVarsets(eq("dummy-token"), eq("https://app.terraform.io/api/v2/workspaces/ws-1"), eq("ws-1")))
                .thenReturn(Collections.emptyList());

        // Allowed path prefix is /api/v2
        ResponseEntity<?> validResponse =
                controller.getWorkspaceVarsets("https://app.terraform.io/api/v2/workspaces/ws-1", "dummy-token", "ws-1");
        assertEquals(HttpStatus.OK, validResponse.getStatusCode());

        // Non-matching path prefix
        ResponseEntity<?> invalidResponse =
                controller.getWorkspaceVarsets("https://app.terraform.io/internal/admin", "dummy-token", "ws-1");

        assertEquals(HttpStatus.FORBIDDEN, invalidResponse.getStatusCode());
    }

    @Test
    void testNullOrMalformedUrlIsBlocked() {
        ResponseEntity<List<WorkspaceImport.WorkspaceData>> nullResponse =
                controller.getWorkspaces(null, "dummy-token", "my-org");
        assertEquals(HttpStatus.FORBIDDEN, nullResponse.getStatusCode());

        ResponseEntity<List<WorkspaceImport.WorkspaceData>> emptyResponse =
                controller.getWorkspaces("   ", "dummy-token", "my-org");
        assertEquals(HttpStatus.FORBIDDEN, emptyResponse.getStatusCode());

        ResponseEntity<List<WorkspaceImport.WorkspaceData>> malformedResponse =
                controller.getWorkspaces("not-a-valid-url", "dummy-token", "my-org");
        assertEquals(HttpStatus.FORBIDDEN, malformedResponse.getStatusCode());
    }
}
