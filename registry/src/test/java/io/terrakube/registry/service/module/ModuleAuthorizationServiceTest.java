package io.terrakube.registry.service.module;

import io.terrakube.registry.service.search.CommonSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModuleAuthorizationServiceTest {

    @Mock
    private CommonSearchService commonSearchService;

    private ModuleAuthorizationService authService;
    private final String secret = Base64.getUrlEncoder().withoutPadding().encodeToString("my-super-secret-key-32-bytes-long!".getBytes());

    @BeforeEach
    void setUp() {
        authService = new ModuleAuthorizationService(
                commonSearchService,
                "http://localhost:8080",
                secret,
                "super-admin-group",
                "DEX"
        );
    }

    @Test
    void testIsAuthorized_NullOrUnauthenticated_ReturnsFalse() {
        assertFalse(authService.isAuthorized(null, "my-org"));
    }

    @Test
    void testIsAuthorized_TerrakubeInternal_ReturnsTrue() {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("iss", "TerrakubeInternal", "sub", "internal")
        );
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());

        assertTrue(authService.isAuthorized(token, "my-org"));
    }

    @Test
    void testIsAuthorized_InstanceOwner_ReturnsTrue() {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("iss", "https://dex.example.com", "groups", List.of("super-admin-group"))
        );
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());

        assertTrue(authService.isAuthorized(token, "my-org"));
    }

    @Test
    void testIsAuthorized_TeamMember_ReturnsTrue() {
        when(commonSearchService.getOrganizationId("my-org")).thenReturn("org-uuid-123");
        authService.setCachedTeams("org-uuid-123", Set.of("dev-team", "ops-team"));

        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("iss", "https://dex.example.com", "groups", List.of("dev-team"))
        );
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());

        assertTrue(authService.isAuthorized(token, "my-org"));
    }

    @Test
    void testIsAuthorized_NonMember_ReturnsFalse() {
        when(commonSearchService.getOrganizationId("my-org")).thenReturn("org-uuid-123");
        authService.setCachedTeams("org-uuid-123", Set.of("dev-team", "ops-team"));

        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("iss", "https://dex.example.com", "groups", List.of("other-team"))
        );
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());

        assertFalse(authService.isAuthorized(token, "my-org"));
    }
}
