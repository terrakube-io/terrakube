package io.terrakube.executor.configuration;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.executor.configuration.security.InternalSecretLoader;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.JobContextHolder;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurityImpl;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TerrakubeClientConfigurationTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    @Mock
    private WorkspaceSecurity mockWorkspaceSecurity;

    @BeforeEach
    void setUp() throws IOException {
        lastAuthHeader.set(null);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = "{\"data\":[]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        JobContextHolder.clear();
    }

    @Test
    void securityEnabled_generatesOneMinuteExecutorServiceToken() {
        when(mockWorkspaceSecurity.generateAccessToken(1)).thenReturn("test-executor-service-token-123");

        RestClientProperties properties = new RestClientProperties();
        properties.setUrl("http://localhost:" + port);
        properties.setEnableSecurity(true);

        TerrakubeClientConfiguration config = new TerrakubeClientConfiguration();
        TerrakubeClient client = config.terrakubeClient(properties, mockWorkspaceSecurity);

        assertNotNull(client);
        try {
            client.getAllOrganizations();
        } catch (Exception ignored) {
        }

        assertEquals("Bearer test-executor-service-token-123", lastAuthHeader.get());
        verify(mockWorkspaceSecurity, times(1)).generateAccessToken(1);
        verify(mockWorkspaceSecurity, never()).generateAccessToken(anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void securityEnabled_withJobContext_stillGeneratesExecutorServiceTokenWithoutStepScope() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-123");
        job.setWorkspaceId("ws-456");
        job.setJobId("job-789");
        job.setStepId("step-abc");
        JobContextHolder.set(job);

        when(mockWorkspaceSecurity.generateAccessToken(1)).thenReturn("pure-executor-service-token");

        RestClientProperties properties = new RestClientProperties();
        properties.setUrl("http://localhost:" + port);
        properties.setEnableSecurity(true);

        TerrakubeClientConfiguration config = new TerrakubeClientConfiguration();
        TerrakubeClient client = config.terrakubeClient(properties, mockWorkspaceSecurity);

        try {
            client.getAllOrganizations();
        } catch (Exception ignored) {
        }

        assertEquals("Bearer pure-executor-service-token", lastAuthHeader.get());
        // Must strictly request a 1-minute service token and NEVER request a workspace/step scoped runner token
        verify(mockWorkspaceSecurity, times(1)).generateAccessToken(1);
        verify(mockWorkspaceSecurity, never()).generateAccessToken(anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void securityDisabled_doesNotSendAuthorizationHeader() {
        RestClientProperties properties = new RestClientProperties();
        properties.setUrl("http://localhost:" + port);
        properties.setEnableSecurity(false);

        TerrakubeClientConfiguration config = new TerrakubeClientConfiguration();
        TerrakubeClient client = config.terrakubeClient(properties, mockWorkspaceSecurity);

        try {
            client.getAllOrganizations();
        } catch (Exception ignored) {
        }

        assertNull(lastAuthHeader.get());
        verifyNoInteractions(mockWorkspaceSecurity);
    }

    @Test
    void realWorkspaceSecurity_generatedTokenIsExecutorServiceWithOneMinuteExpiration() {
        String secret = Base64.getEncoder().encodeToString(RandomStringUtils.secure().nextAlphanumeric(64).getBytes(StandardCharsets.UTF_8));
        RestClientProperties restProperties = new RestClientProperties();
        restProperties.setUrl("http://localhost:" + port);
        WorkspaceSecurity realWorkspaceSecurity = new WorkspaceSecurityImpl(
                restProperties,
                "terrakube-registry.localhost",
                "https://terrakube-api.localhost",
                new InternalSecretLoader(secret, null)
        );

        Instant before = Instant.now();
        String token = realWorkspaceSecurity.generateAccessToken(1);
        Instant after = Instant.now();

        assertNotNull(token);

        Claims claims = Jwts.parser()
                .setSigningKey(Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret)))
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertEquals("TerrakubeInternal", claims.getIssuer());
        assertEquals("TerrakubeInternal (EXECUTOR)", claims.getSubject());
        assertNull(claims.get("workspaceId"), "Service token must NOT contain workspaceId claim");
        assertNull(claims.get("jobId"), "Service token must NOT contain jobId claim");
        assertNull(claims.get("stepId"), "Service token must NOT contain stepId claim");

        Date expiration = claims.getExpiration();
        assertNotNull(expiration);
        long validitySeconds = Duration.between(before, expiration.toInstant()).getSeconds();
        assertTrue(validitySeconds >= 55 && validitySeconds <= 65, "Token must be valid for ~60 seconds (1 minute), was: " + validitySeconds);
    }
}
