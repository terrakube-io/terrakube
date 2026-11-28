package io.terrakube.api.plugin.token;

import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.token.group.Group;
import io.terrakube.api.rs.token.pat.Pat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalTokenStatusControllerTest {

    @Mock
    private PatRepository patRepository;

    @Mock
    private TeamTokenRepository teamTokenRepository;

    private InternalTokenStatusController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalTokenStatusController(patRepository, teamTokenRepository);
    }

    private static JwtAuthenticationToken jwtAuth(Map<String, Object> claims) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("alg", "HS256"),
                claims
        );
        return new JwtAuthenticationToken(jwt);
    }

    private static JwtAuthenticationToken registryServiceAuth() {
        return jwtAuth(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
    }

    @Test
    void getTokenStatus_registryService_activePat_returnsTrueWithNoStore() {
        UUID tokenId = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setId(tokenId);
        pat.setDeleted(false);
        when(patRepository.findById(tokenId)).thenReturn(Optional.of(pat));

        ResponseEntity<InternalTokenStatusController.TokenStatusResponse> response =
                controller.getTokenStatus(tokenId.toString(), registryServiceAuth());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().active());
        verifyNoInteractions(teamTokenRepository);
    }

    @Test
    void getTokenStatus_registryService_deletedPat_returnsFalse() {
        UUID tokenId = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setId(tokenId);
        pat.setDeleted(true);
        when(patRepository.findById(tokenId)).thenReturn(Optional.of(pat));

        ResponseEntity<InternalTokenStatusController.TokenStatusResponse> response =
                controller.getTokenStatus(tokenId.toString(), registryServiceAuth());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().active());
    }

    @Test
    void getTokenStatus_registryService_activeTeamToken_returnsTrue() {
        UUID tokenId = UUID.randomUUID();
        Group group = new Group();
        group.setId(tokenId);
        group.setDeleted(false);
        when(patRepository.findById(tokenId)).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(tokenId)).thenReturn(Optional.of(group));

        ResponseEntity<InternalTokenStatusController.TokenStatusResponse> response =
                controller.getTokenStatus(tokenId.toString(), registryServiceAuth());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().active());
    }

    @Test
    void getTokenStatus_registryService_deletedOrUnknownOrMalformedToken_returnsFalse() {
        UUID deletedGroupId = UUID.randomUUID();
        Group deletedGroup = new Group();
        deletedGroup.setId(deletedGroupId);
        deletedGroup.setDeleted(true);
        when(patRepository.findById(deletedGroupId)).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(deletedGroupId)).thenReturn(Optional.of(deletedGroup));

        UUID unknownId = UUID.randomUUID();
        when(patRepository.findById(unknownId)).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(unknownId)).thenReturn(Optional.empty());

        ResponseEntity<InternalTokenStatusController.TokenStatusResponse> deletedResp =
                controller.getTokenStatus(deletedGroupId.toString(), registryServiceAuth());
        assertEquals(HttpStatus.OK, deletedResp.getStatusCode());
        assertFalse(deletedResp.getBody().active());

        ResponseEntity<InternalTokenStatusController.TokenStatusResponse> unknownResp =
                controller.getTokenStatus(unknownId.toString(), registryServiceAuth());
        assertEquals(HttpStatus.OK, unknownResp.getStatusCode());
        assertFalse(unknownResp.getBody().active());

        ResponseEntity<InternalTokenStatusController.TokenStatusResponse> malformedResp =
                controller.getTokenStatus("not-a-uuid", registryServiceAuth());
        assertEquals(HttpStatus.OK, malformedResp.getStatusCode());
        assertFalse(malformedResp.getBody().active());
    }

    @Test
    void getTokenStatus_nonRegistryCallers_returnForbidden() {
        String tokenId = UUID.randomUUID().toString();

        Authentication[] unauthorizedCallers = new Authentication[]{
                null,
                new TestingAuthenticationToken("user", "creds"),
                jwtAuth(Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (EXECUTOR)")),
                jwtAuth(Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (EXECUTOR)", "workspaceId", UUID.randomUUID().toString())),
                jwtAuth(Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (TOKEN)")),
                jwtAuth(Map.of("iss", "Terrakube", "jti", UUID.randomUUID().toString())),
                jwtAuth(Map.of("iss", "https://dex.example.com", "email", "user@example.com"))
        };

        for (Authentication caller : unauthorizedCallers) {
            ResponseEntity<InternalTokenStatusController.TokenStatusResponse> response =
                    controller.getTokenStatus(tokenId, caller);
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        }
        verifyNoInteractions(patRepository, teamTokenRepository);
    }
}
