package io.terrakube.executor.service.workspace.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSecurityTest {

    private static final String SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "super-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256!".getBytes(StandardCharsets.UTF_8));
    private static final String CREDENTIALS_FILE_NAME = "/.terraformrc";

    private WorkspaceSecurityImpl workspaceSecurity;
    private File terraformrcFile;

    @BeforeEach
    void setUp() {
        RestClientProperties clientProperties = new RestClientProperties();
        workspaceSecurity = new WorkspaceSecurityImpl(
                clientProperties,
                "registry.example.com",
                "https://api.example.com",
                SECRET
        );
        terraformrcFile = new File(
                FilenameUtils.separatorsToSystem(
                        FileUtils.getUserDirectoryPath().concat(CREDENTIALS_FILE_NAME)
                )
        );
    }

    @AfterEach
    void tearDown() {
        if (terraformrcFile.exists()) {
            FileUtils.deleteQuietly(terraformrcFile);
        }
    }

    @Test
    void generatesScopedTokenWithExpectedClaimsAndLifetime() {
        String token = workspaceSecurity.generateAccessToken(60, "org-1", "ws-1", "job-1", "step-1");
        assertNotNull(token);

        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(SECRET));
        Claims claims = Jwts.parser()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertEquals("TerrakubeInternal", claims.getIssuer());
        assertEquals("TerrakubeInternal (EXECUTOR)", claims.getSubject());
        assertEquals("org-1", claims.get("organizationId"));
        assertEquals("ws-1", claims.get("workspaceId"));
        assertEquals("job-1", claims.get("jobId"));
        assertEquals("step-1", claims.get("stepId"));
        assertEquals("job-1-step-1", claims.getId());

        long lifetimeMinutes = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / (60 * 1000);
        assertEquals(60, lifetimeMinutes);
    }

    @Test
    void addAndCleanTerraformCredentialsLifecycle() throws Exception {
        workspaceSecurity.addTerraformCredentials("org-1", "ws-1", "job-1", "step-1");

        assertTrue(terraformrcFile.exists(), "~/.terraformrc must exist after adding credentials");
        String content = FileUtils.readFileToString(terraformrcFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("registry.example.com"));
        assertTrue(content.contains("api.example.com"));

        workspaceSecurity.cleanTerraformCredentials();

        assertFalse(terraformrcFile.exists(), "~/.terraformrc must be deleted after cleanTerraformCredentials");
    }

    @Test
    void cleanTerraformCredentialsIsIdempotentWhenFileDoesNotExist() {
        if (terraformrcFile.exists()) {
            FileUtils.deleteQuietly(terraformrcFile);
        }
        workspaceSecurity.cleanTerraformCredentials();
        assertFalse(terraformrcFile.exists());
    }
}
