package io.terrakube.api.plugin.token.team;

import io.terrakube.api.plugin.token.TokenIssuancePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamTokenControllerTest {

    @Mock
    private TeamTokenService teamTokenService;

    @Mock
    private TokenIssuancePolicy tokenIssuancePolicy;

    private TeamTokenController controller;

    @BeforeEach
    void setUp() {
        controller = new TeamTokenController(null, null, teamTokenService, null, "admin", tokenIssuancePolicy);
    }

    private JwtAuthenticationToken createJwtAuth() {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("sub", "user@example.com", "email", "user@example.com", "groups", List.of("DEV_TEAM")));
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    void createToken_whenCallerIsNotMemberOfGroup_throwsForbidden403() {
        JwtAuthenticationToken auth = createJwtAuth();
        TeamTokenController.GroupTokenRequest request = new TeamTokenController.GroupTokenRequest();
        request.setGroup("OTHER_TEAM");
        request.setDays(7);

        when(teamTokenService.createTeamToken(eq("OTHER_TEAM"), eq(7), eq(0), eq(0), any(), eq(auth)))
                .thenReturn("");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.createToken(request, auth));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("OTHER_TEAM"));
    }

    @Test
    void createToken_whenCallerIsMemberOfGroup_returnsCreated201WithToken() {
        JwtAuthenticationToken auth = createJwtAuth();
        TeamTokenController.GroupTokenRequest request = new TeamTokenController.GroupTokenRequest();
        request.setGroup("DEV_TEAM");
        request.setDays(7);

        when(teamTokenService.createTeamToken(eq("DEV_TEAM"), eq(7), eq(0), eq(0), any(), eq(auth)))
                .thenReturn("valid-jwt-token");

        ResponseEntity<TeamTokenController.TeamToken> response = controller.createToken(request, auth);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("valid-jwt-token", response.getBody().getToken());
    }

    @Test
    void createToken_whenPrincipalNotJwt_throwsUnauthorized401() {
        TeamTokenController.GroupTokenRequest request = new TeamTokenController.GroupTokenRequest();
        request.setGroup("DEV_TEAM");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.createToken(request, () -> "non-jwt-principal"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }
}

